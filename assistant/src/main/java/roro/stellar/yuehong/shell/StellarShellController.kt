package roro.stellar.yuehong.shell

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import roro.stellar.Stellar
import roro.stellar.StellarRemoteProcess
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class StellarStatus {
    Checking,
    Unavailable,
    NeedsPermission,
    Granted,
}

data class OutputLine(val text: String, val isError: Boolean = false)

data class ShellFileInstallResult(val exitCode: Int, val output: String)

internal const val STELLAR_PAYLOAD_DESTINATION = "/data/local/tmp/preload.so"
internal const val STELLAR_PAYLOAD_MODE = "0755"
internal const val KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION = "/data/local/tmp/yhroot_ksu_activate.sh"
internal const val KERNEL_SU_ACTIVATION_SCRIPT_LOG = "/data/local/tmp/yhroot_ksu_activate.log"
internal const val KERNEL_SU_ACTIVATION_SCRIPT_MODE = "0700"

class StellarShellController : AutoCloseable {
    var status by mutableStateOf(StellarStatus.Checking)
        private set

    var isRunning by mutableStateOf(false)
        private set

    var permissionRequiredNotice by mutableStateOf(false)
        private set

    var commandStartToken by mutableIntStateOf(0)
        private set

    val output = mutableStateListOf<OutputLine>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var registered = false
    private var permissionRequestInFlight = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private var pendingPermissionDeniedAction: (() -> Unit)? = null

    @Volatile
    private var process: StellarRemoteProcess? = null

    private var currentDirectory = "/storage/emulated/0"

    private val binderReceivedListener = Stellar.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener = Stellar.OnBinderDeadListener {
        postToMain {
            status = StellarStatus.Unavailable
            failPendingPermissionRequest(showNotice = pendingPermissionAction != null)
        }
    }
    private val permissionResultListener =
        Stellar.OnRequestPermissionResultListener { requestCode, allowed, _ ->
            if (requestCode == REQUEST_CODE) {
                postToMain {
                    permissionRequestInFlight = false
                    if (allowed) {
                        status = StellarStatus.Granted
                        permissionRequiredNotice = false
                        val action = pendingPermissionAction
                        clearPendingPermissionActions()
                        action?.invoke()
                    } else {
                        status = StellarStatus.NeedsPermission
                        failPendingPermissionRequest(showNotice = true)
                    }
                }
            }
        }

    fun register() {
        if (registered) return
        registered = true
        runCatching {
            Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
            Stellar.addBinderDeadListener(binderDeadListener)
            Stellar.addRequestPermissionResultListener(permissionResultListener)
        }
        refreshStatus()
    }

    fun refreshStatus() {
        val next = runCatching {
            when {
                !Stellar.pingBinder() -> StellarStatus.Unavailable
                Stellar.checkSelfPermission() -> {
                    StellarStatus.Granted
                }
                else -> StellarStatus.NeedsPermission
            }
        }.getOrDefault(StellarStatus.Unavailable)
        postToMain {
            status = next
            if (next == StellarStatus.Granted) permissionRequiredNotice = false
        }
    }

    fun requestPermission() {
        if (hasPermissionNow()) {
            permissionRequestInFlight = false
            status = StellarStatus.Granted
            permissionRequiredNotice = false
            val action = pendingPermissionAction
            clearPendingPermissionActions()
            action?.invoke()
            return
        }

        beginPermissionRequest()
    }

    fun ensurePermission(
        onGranted: () -> Unit,
        onDenied: () -> Unit = {},
    ) {
        if (hasPermissionNow()) {
            permissionRequestInFlight = false
            status = StellarStatus.Granted
            clearPendingPermissionActions()
            onGranted()
            return
        }

        pendingPermissionAction = onGranted
        pendingPermissionDeniedAction = onDenied
        beginPermissionRequest()
    }

    private fun beginPermissionRequest() {
        permissionRequiredNotice = false
        val binderAvailable = runCatching { Stellar.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) {
            status = StellarStatus.Unavailable
            failPendingPermissionRequest(showNotice = true)
            return
        }

        status = StellarStatus.NeedsPermission
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        runCatching { Stellar.requestPermission(requestCode = REQUEST_CODE) }.onFailure {
            status = StellarStatus.NeedsPermission
            failPendingPermissionRequest(showNotice = true)
        }
    }

    fun execute(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || isRunning) return

        ensurePermission(onGranted = {
            if (!isRunning) startCommand(trimmed)
        })
    }

    fun dismissPermissionRequiredNotice() {
        permissionRequiredNotice = false
    }

    @Suppress("DEPRECATION")
    private fun startCommand(
        command: String,
        onComplete: ((exitCode: Int, output: List<String>) -> Unit)? = null,
    ) {
        commandStartToken++
        val commandToRun = handleDirectoryCommand(command) ?: return
        output += OutputLine("$ $command")
        isRunning = true

        executor.execute {
            var remote: StellarRemoteProcess? = null
            var exitCode = -1
            val capturedOutput = mutableListOf<String>()
            try {
                remote = Stellar.newProcess(
                    arrayOf("sh", "-c", "$commandToRun 2>&1"),
                    null,
                    currentDirectory,
                )
                process = remote
                remote.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        capturedOutput += line
                        postToMain { output += OutputLine(line) }
                    }
                }
                exitCode = remote.waitFor()
                if (exitCode != 0) {
                    postToMain { output += OutputLine("[exit code $exitCode]", isError = true) }
                }
            } catch (error: Throwable) {
                val message = error.message?.takeIf(String::isNotBlank)
                    ?: error.javaClass.simpleName
                postToMain { output += OutputLine(message, isError = true) }
            } finally {
                runCatching { remote?.destroy() }
                process = null
                postToMain {
                    isRunning = false
                    onComplete?.invoke(exitCode, capturedOutput.toList())
                }
            }
        }
    }

    private fun failPendingPermissionRequest(showNotice: Boolean) {
        permissionRequestInFlight = false
        val deniedAction = pendingPermissionDeniedAction
        clearPendingPermissionActions()
        if (showNotice) permissionRequiredNotice = true
        deniedAction?.invoke()
    }

    private fun clearPendingPermissionActions() {
        pendingPermissionAction = null
        pendingPermissionDeniedAction = null
    }

    private fun hasPermissionNow(): Boolean = runCatching {
        Stellar.pingBinder() &&
            Stellar.checkSelfPermission()
    }.getOrDefault(false)

    fun stop() {
        runCatching { process?.destroy() }
    }

    fun clear() {
        if (!isRunning) output.clear()
    }

    fun appendOutput(text: String, isError: Boolean = false) {
        val normalized = text.trimEnd()
        if (normalized.isEmpty()) return
        postToMain { output += OutputLine(normalized, isError) }
    }

    @Suppress("DEPRECATION")
    fun removeExistingPayload(): ShellFileInstallResult {
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "rm -f -- '$STELLAR_PAYLOAD_DESTINATION' && " +
                    "[ ! -e '$STELLAR_PAYLOAD_DESTINATION' ] && [ ! -L '$STELLAR_PAYLOAD_DESTINATION' ]",
            ),
            null,
            "/",
        )
        return try {
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    fun installPayload(source: File): ShellFileInstallResult {
        require(source.isFile) { "Downloaded resource file is missing" }
        val quotedDestination = "'$STELLAR_PAYLOAD_DESTINATION'"
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "cat > $quotedDestination && chmod $STELLAR_PAYLOAD_MODE $quotedDestination && " +
                    "[ -f $quotedDestination ] && [ -x $quotedDestination ]",
            ),
            null,
            "/",
        )
        return try {
            source.inputStream().use { input ->
                remote.outputStream.use { output -> input.copyTo(output) }
            }
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    fun installKernelSuActivationScript(source: File): ShellFileInstallResult {
        require(source.isFile) { "KernelSU activation script is missing" }
        val quotedDestination = "'$KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION'"
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "cat > $quotedDestination && " +
                    "chmod $KERNEL_SU_ACTIVATION_SCRIPT_MODE $quotedDestination && " +
                    "[ -f $quotedDestination ] && [ -x $quotedDestination ]",
            ),
            null,
            "/",
        )
        return try {
            source.inputStream().use { input ->
                remote.outputStream.use { output -> input.copyTo(output) }
            }
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    fun removeKernelSuActivationScript(): ShellFileInstallResult {
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "rm -f -- '$KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION' && " +
                    "[ ! -e '$KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION' ] && " +
                    "[ ! -L '$KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION' ]",
            ),
            null,
            "/",
        )
        return try {
            remote.outputStream.close()
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
    }

    private fun handleDirectoryCommand(command: String): String? {
        if (command == "cd" || command.startsWith("cd ")) {
            val target = command.removePrefix("cd").trim().ifBlank { "/storage/emulated/0" }
            currentDirectory = when {
                target == "~" -> "/storage/emulated/0"
                target == ".." -> currentDirectory.substringBeforeLast('/', "").ifBlank { "/" }
                target.startsWith('/') -> target
                else -> "${currentDirectory.trimEnd('/')}/$target"
            }
            output += OutputLine("$ $command")
            output += OutputLine(currentDirectory)
            return null
        }
        return command
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    override fun close() {
        stop()
        if (registered) {
            runCatching { Stellar.removeBinderReceivedListener(binderReceivedListener) }
            runCatching { Stellar.removeBinderDeadListener(binderDeadListener) }
            runCatching { Stellar.removeRequestPermissionResultListener(permissionResultListener) }
        }
        registered = false
        executor.shutdownNow()
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
