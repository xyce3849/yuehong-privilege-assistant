package roro.stellar.yuehong.shell

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import roro.stellar.Stellar
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 提权流程的各个阶段
enum class EscalationStage {
    Idle,
    CheckingPermission,
    CollectingDeviceInfo,
    ReportingToServer,
    ValidatingCommand,
    DownloadingResources,
    ImportingLocalResource,
    InstallingResources,
    ExecutingPayload,
    CheckingRoot,
    ActivatingKernelSu,
    Done,
}

// 提权结果
sealed interface EscalationResult {
    data object Idle : EscalationResult
    data class DeviceNotSupported(val profile: DeviceProfile) : EscalationResult
    data class RootConfirmed(val output: String, val suPath: String) : EscalationResult
    data class KernelSuActivated(val output: String) : EscalationResult
    data class KernelSuActivationFailure(val reason: String) : EscalationResult
    data class Failure(val stage: EscalationStage, val reason: String) : EscalationResult
}

// 提权编排器：串联 Stellar 权限检查、设备信息上报、命令校验、命令执行、结果检测
class PrivilegeEscalator(
    context: Context,
    private val shell: StellarShellController,
    private val compatibilityApi: CompatibilityApi,
    private val resourceDownloader: PayloadResourceDownloader,
) : AutoCloseable {
    var stage by mutableStateOf(EscalationStage.Idle)
        private set

    var result by mutableStateOf<EscalationResult>(EscalationResult.Idle)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val appContext = context.applicationContext

    fun start() {
        startInternal(localRequest = null)
    }

    fun startWithLocalPayload(uri: Uri, command: String) {
        startInternal(LocalPayloadRequest(uri, command.trim()))
    }

    private fun startInternal(localRequest: LocalPayloadRequest?) {
        if (stage != EscalationStage.Idle && stage != EscalationStage.Done) return

        resetState()
        log(
            if (localRequest == null) "starting online privilege escalation"
            else "starting fully local privilege escalation",
        )
        updateStage(EscalationStage.CheckingPermission)
        shell.ensurePermission(
            onGranted = { executor.execute { runFlow(localRequest) } },
            onDenied = ::cancelForMissingPermission,
        )
    }

    fun reset() {
        resetState()
    }

    fun activateKernelSu() {
        mainHandler.post {
            val rootResult = result as? EscalationResult.RootConfirmed ?: return@post
            if (stage != EscalationStage.Done) return@post
            stage = EscalationStage.ActivatingKernelSu
            result = EscalationResult.Idle
            executor.execute { runKernelSuActivation(rootResult.suPath) }
        }
    }

    fun uploadUnsupportedProfile(
        profile: DeviceProfile,
        onResult: (DeviceProfileUploadResult) -> Unit,
    ) {
        compatibilityApi.uploadUnsupportedProfile(profile, onResult)
    }

    private fun resetState() {
        mainHandler.post {
            stage = EscalationStage.Idle
            result = EscalationResult.Idle
        }
    }

    // StellarShellController 会显示专用权限提示；这里结束流程但不再生成通用失败结果，
    // 避免“需要 Stellar 权限”和“提权失败”两个提示同时出现。
    private fun cancelForMissingPermission() {
        resetState()
    }

    private fun runFlow(localRequest: LocalPayloadRequest?) {
        try {
            val validatedCommand: String
            val validatedSuPath: String
            val prepared: PreparedPayload
            val commandSource: String

            if (localRequest == null) {
                // 在线模式维持现有协议：服务端完成设备档案匹配并下发命令、su 路径和下载资源。
                updateStage(EscalationStage.CollectingDeviceInfo)
                val profile = DeviceProfile.collect()
                log(
                    "modelName=${profile.modelName}, kernel=${profile.kernelVersion}, " +
                        "system=${profile.systemVersion}",
                )

                updateStage(EscalationStage.ReportingToServer)
                val payload = reportProfile(profile)
                log("server returned payloadCommand for payloadId=${payload.payloadId}")
                log("server payloadCommand: ${payload.command}")
                log("server suPath: ${payload.suPath}")

                updateStage(EscalationStage.ValidatingCommand)
                validatedCommand = validateCommand(payload.command)
                if (validatedCommand.isEmpty()) {
                    fail(EscalationStage.ValidatingCommand, "server returned invalid payloadCommand format")
                    return
                }
                validatedSuPath = validateSuPath(payload.suPath)
                if (validatedSuPath.isEmpty()) {
                    fail(EscalationStage.ValidatingCommand, "server returned invalid suPath")
                    return
                }
                log("server payloadCommand validated as a single shell command")
                log("server su path validated: $validatedSuPath")
                prepared = prepareOnlinePayload(payload)
                commandSource = "server"
            } else {
                // 本地模式不收集或上报设备信息，也不调用任何 DMKPZ 接口。
                updateStage(EscalationStage.ValidatingCommand)
                validatedCommand = validateCommand(localRequest.command)
                if (validatedCommand.isEmpty()) {
                    fail(EscalationStage.ValidatingCommand, "local payload command is empty or invalid")
                    return
                }
                validatedSuPath = LOCAL_PAYLOAD_SU_PATH
                log("local payload command validated: $validatedCommand")
                log("local payload su path fixed at $validatedSuPath")
                prepared = prepareLocalPayload(localRequest.uri)
                commandSource = "local"
            }
            log("fixed resource destination: $STELLAR_PAYLOAD_DESTINATION")
            log("fixed resource mode: $STELLAR_PAYLOAD_MODE")

            try {
                updateStage(EscalationStage.InstallingResources)
                log("removing old resource: $STELLAR_PAYLOAD_DESTINATION")
                val removal = shell.removeExistingPayload()
                if (removal.output.isNotBlank()) log("old resource removal output: ${removal.output.trim()}")
                log("old resource removal exit code=${removal.exitCode}")
                if (removal.exitCode != 0) {
                    fail(
                        EscalationStage.InstallingResources,
                        "failed to remove old resource at $STELLAR_PAYLOAD_DESTINATION",
                    )
                    return
                }
                log("old resource removed successfully")
                log("writing resource to $STELLAR_PAYLOAD_DESTINATION")
                val install = shell.installPayload(prepared.file)
                if (install.output.isNotBlank()) log("resource install output: ${install.output.trim()}")
                log("resource install exit code=${install.exitCode}")
                if (install.exitCode != 0) {
                    fail(EscalationStage.InstallingResources, "resource install failed with code ${install.exitCode}")
                    return
                }
                log("resource installed successfully: $STELLAR_PAYLOAD_DESTINATION")
                log("resource permission set successfully: $STELLAR_PAYLOAD_MODE")
            } finally {
                prepared.file.delete()
            }

            // 通过 Stellar shell 执行在线服务端命令或用户选择的本地命令。
            //    此时不能要求 su 已经可用，因为该命令本身负责完成提权。
            updateStage(EscalationStage.ExecutingPayload)
            log("executing $commandSource payload command: $validatedCommand")
            val execution = executeCommand(validatedCommand)
            log("$commandSource command output: ${execution.output.trim().ifBlank { "<empty>" }}")
            log("$commandSource command exit code=${execution.exitCode}")
            if (execution.exitCode != 0 && localRequest == null) {
                fail(EscalationStage.ExecutingPayload, "$commandSource command exited with code ${execution.exitCode}")
                return
            }
            if (execution.exitCode != 0) {
                log("local command exit code is non-zero; continuing with the fixed su availability check")
            }

            // 在线模式使用服务端路径，本地模式固定使用 /data/local/tmp/su。
            //    这里按用户要求只看输出中的 uid=0，不把该命令的退出码作为成功条件。
            updateStage(EscalationStage.CheckingRoot)
            log("checking root with su path: $validatedSuPath -c 'id'")
            val rootCheck = executeSuCommand(validatedSuPath, ROOT_CHECK_INNER_COMMAND)
            val rootOutput = rootCheck.output.trim()
            val isRoot = ROOT_UID_PATTERN.containsMatchIn(rootOutput)
            log("root check output: $rootOutput")
            log("root check exit code=${rootCheck.exitCode} (not used for success decision)")
            log("root detected=$isRoot (based on uid=0 output)")

            updateStage(EscalationStage.Done)
            mainHandler.post {
                result = if (isRoot) {
                    EscalationResult.RootConfirmed(
                        execution.output.trim().ifBlank { rootOutput },
                        validatedSuPath,
                    )
                } else {
                    EscalationResult.Failure(
                        EscalationStage.CheckingRoot,
                        "uid=0 was not found in output of $validatedSuPath -c 'id'",
                    )
                }
            }
        } catch (error: DeviceNotSupportedException) {
            showDeviceNotSupported(error.profile)
        } catch (error: Throwable) {
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            fail(stage, reason)
        }
    }

    private fun prepareOnlinePayload(payload: PayloadInfo): PreparedPayload {
        val resource = payload.resources.single()
        log("server resource name: ${resource.name}")
        log("server resource size: ${resource.size} bytes")
        log("server resource SHA-256: ${resource.sha256}")
        updateStage(EscalationStage.DownloadingResources)

        var candidate = resource
        var previousError: IOException? = null
        repeat(MAX_DOWNLOAD_ROUTE_ATTEMPTS) {
            try {
                val downloaded = downloadFrom(candidate)
                log("server resource downloaded successfully: ${resource.name}")
                log("resource size and SHA-256 verification succeeded (${resource.size} bytes)")
                if (!synchronousConfirmResourceDownload(
                        payload.payloadId,
                        candidate.sessionId,
                        candidate.attemptId,
                    )
                ) {
                    log("download succeeded, but route statistics confirmation was not delivered")
                }
                return PreparedPayload(downloaded)
            } catch (downloadError: IOException) {
                previousError = downloadError
                log("route ${candidate.routeName} failed: ${downloadError.message ?: downloadError.javaClass.simpleName}")
                log("reporting failure and requesting the next prioritized route")
                val nextTicket = when (val ticketResult = synchronousRequestNextTicket(
                    payload.payloadId,
                    candidate.sessionId,
                    candidate.attemptId,
                    downloadFailureReason(downloadError),
                )) {
                    is ResourceTicketResult.Success -> ticketResult
                    ResourceTicketResult.Exhausted ->
                        throw IOException("All configured download routes are exhausted", downloadError)
                    is ResourceTicketResult.Failure -> {
                        val error = IOException("Next route ticket request failed: ${ticketResult.reason}")
                        error.addSuppressed(downloadError)
                        throw error
                    }
                }
                if (!nextTicket.sha256.equals(resource.sha256, ignoreCase = true)) {
                    throw IOException("Next route ticket SHA-256 does not match compatibility metadata", downloadError)
                }
                candidate = resource.copy(
                    url = nextTicket.url,
                    route = nextTicket.route,
                    routeName = nextTicket.routeName,
                    sessionId = nextTicket.sessionId,
                    attemptId = nextTicket.attemptId,
                    hasMoreRoutes = nextTicket.hasMoreRoutes,
                )
                log("switching to route ${candidate.routeName} (${candidate.route})")
            }
        }
        throw IOException("Download route safety limit exceeded", previousError)
    }

    private fun downloadFrom(candidate: PayloadResource): File {
        log("starting route ${candidate.routeName} (${candidate.route}) resource download: ${candidate.name}")
        var lastProgressBucket = -1
        return resourceDownloader.download(candidate) { progress ->
            val bucket = if (progress.percent == 100) 100 else (progress.percent / 5) * 5
            if (bucket != lastProgressBucket) {
                lastProgressBucket = bucket
                log(
                    "${candidate.routeName} resource download progress: ${progress.percent}% " +
                        "(${progress.downloadedBytes}/${progress.totalBytes} bytes)",
                )
            }
        }
    }

    private fun downloadFailureReason(error: IOException): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "sha-256" in message || "sha256" in message -> "sha256_mismatch"
            "size mismatch" in message -> "size_mismatch"
            "timeout" in message || "timed out" in message -> "timeout"
            "http " in message -> "http_error"
            else -> "download_failed"
        }
    }

    private fun prepareLocalPayload(uri: Uri): PreparedPayload {
        updateStage(EscalationStage.ImportingLocalResource)
        val resolver = appContext.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.replace(Regex("[\\r\\n]"), " ")?.take(MAX_LOCAL_DISPLAY_NAME_LENGTH)
            ?.ifBlank { null }
            ?: "selected payload"

        val cacheDirectory = File(appContext.cacheDir, LOCAL_PAYLOAD_CACHE_DIRECTORY)
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Unable to create the local payload cache")
        }
        val target = File.createTempFile(LOCAL_PAYLOAD_PREFIX, ".bin", cacheDirectory)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Unable to open the selected local payload")
            input.use { source ->
                FileOutputStream(target).use { destination ->
                    val buffer = ByteArray(LOCAL_COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copiedBytes += read
                        if (copiedBytes > MAX_RESOURCE_BYTES) {
                            throw IOException("Selected local payload exceeds the 512 MiB limit")
                        }
                        destination.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    destination.fd.sync()
                }
            }
            if (copiedBytes == 0L) throw IOException("Selected local payload is empty")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            log("local payload selected: $displayName")
            log("local payload size: $copiedBytes bytes")
            log("local payload SHA-256: $sha256")
            PreparedPayload(target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun runKernelSuActivation(suPath: String) {
        val outputs = mutableListOf<String>()
        var failure: Throwable? = null
        var cachedScript: File? = null
        try {
            cachedScript = prepareKernelSuActivationScript()
            log("installing GhostLock-style KernelSU activation script")
            val installation = shell.installKernelSuActivationScript(cachedScript)
            if (installation.output.isNotBlank()) outputs += installation.output.trim()
            if (installation.exitCode != 0) {
                throw IOException("KernelSU activation script installation exited with code ${installation.exitCode}")
            }

            log("running KernelSU activation script through temporary su")
            val execution = executeSuCommand(
                suPath,
                "/system/bin/sh '$KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION'; " +
                    "rc=\$?; cat '$KERNEL_SU_ACTIVATION_SCRIPT_LOG' 2>/dev/null; exit \$rc",
            )
            log("KernelSU activation script exit code=${execution.exitCode}")
            if (execution.output.isNotBlank()) {
                val output = execution.output.trim()
                outputs += output
                log("KernelSU activation script output: $output")
            }
            if (execution.exitCode != 0) {
                throw IOException("KernelSU activation script exited with code ${execution.exitCode}")
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { shell.removeKernelSuActivationScript() }
                .onSuccess { cleanup ->
                    log("KernelSU activation script cleanup exit code=${cleanup.exitCode}")
                    if (cleanup.output.isNotBlank()) log(cleanup.output.trim())
                }
                .onFailure { error ->
                    shell.appendOutput(
                        "[KernelSU 脚本清理失败] ${error.message ?: error.javaClass.simpleName}",
                        isError = true,
                    )
                }
            cachedScript?.delete()
        }

        val activationFailure = failure
        if (activationFailure != null) {
            val reason = activationFailure.message?.takeIf(String::isNotBlank)
                ?: activationFailure.javaClass.simpleName
            shell.appendOutput("[KernelSU 激活失败] $reason", isError = true)
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivationFailure(reason)
            }
        } else {
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivated(outputs.joinToString("\n"))
            }
        }
    }

    private fun prepareKernelSuActivationScript(): File {
        val target = File.createTempFile("yhroot-ksu-", ".sh", appContext.cacheDir)
        return try {
            appContext.assets.open(KERNEL_SU_ACTIVATION_ASSET).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    // 把设备信息 POST 到兼容性服务器，解析返回 JSON 中的 payloadCommand
    private fun reportProfile(profile: DeviceProfile): PayloadInfo {
        val response = synchronousCheck(profile)
        return when (response) {
            is CompatibilityResult.EndpointNotConfigured ->
                throw IOException("Compatibility endpoint not configured")
            is CompatibilityResult.DeviceNotSupported ->
                throw DeviceNotSupportedException(profile)
            is CompatibilityResult.Failure ->
                throw IOException("Server request failed: ${response.reason}")
            is CompatibilityResult.Success -> parsePayload(response.responseBody)
        }
    }

    // 同步调用 CompatibilityApi（阻塞当前线程）
    private fun synchronousCheck(profile: DeviceProfile): CompatibilityResult {
        val latch = java.util.concurrent.CountDownLatch(1)
        var captured: CompatibilityResult = CompatibilityResult.Failure("No result")
        compatibilityApi.check(profile) { result ->
            captured = result
            latch.countDown()
        }
        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            return CompatibilityResult.Failure("Timed out waiting for server response")
        }
        return captured
    }

    private fun synchronousRequestNextTicket(
        resourceId: String,
        sessionId: String,
        attemptId: String,
        failureReason: String,
    ): ResourceTicketResult {
        val latch = java.util.concurrent.CountDownLatch(1)
        var captured: ResourceTicketResult = ResourceTicketResult.Failure("No result")
        compatibilityApi.requestNextTicket(resourceId, sessionId, attemptId, failureReason) { result ->
            captured = result
            latch.countDown()
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            return ResourceTicketResult.Failure("Timed out waiting for next route ticket")
        }
        return captured
    }

    // 解析服务器响应 JSON，提取 payloadCommand 与 payloadId
    // 服务端必须返回 matchMode=exact 且非空的 payloadCommand
    private fun synchronousConfirmResourceDownload(
        resourceId: String,
        sessionId: String,
        attemptId: String,
    ): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var confirmed = false
        compatibilityApi.confirmResourceDownload(resourceId, sessionId, attemptId) { result ->
            confirmed = result
            latch.countDown()
        }
        return latch.await(30, java.util.concurrent.TimeUnit.SECONDS) && confirmed
    }

    private fun parsePayload(body: String): PayloadInfo {
        val json = JSONObject(body)
        if (json.optString("matchMode") != "exact") {
            throw IOException("Server did not confirm strict exact compatibility matching")
        }
        val command = json.optString("payloadCommand")
            .takeIf(String::isNotBlank)
            ?: json.optString("payload_command")
                .takeIf(String::isNotBlank)
            ?: throw IOException("Server response missing payloadCommand")
        val payloadId = json.optString("payloadId").trim()
            .takeIf(String::isNotBlank)
            ?: json.optString("payload_id").trim()
                .takeIf(String::isNotBlank)
            ?: throw IOException("Server response missing payloadId")
        if (payloadId.length > MAX_RESOURCE_ID_LENGTH) {
            throw IOException("Server returned invalid payloadId")
        }
        val suPath = json.optString("suPath")
            .takeIf(String::isNotBlank)
            ?: json.optString("su_path")
                .takeIf(String::isNotBlank)
            ?: json.optString("stellar_su_path")
                .takeIf(String::isNotBlank)
            ?: throw IOException("Server response missing suPath")
        val array = json.optJSONArray("resources")
            ?: throw IOException("Server response missing resources")
        if (array.length() != 1) {
            throw IOException("Server must return exactly one device-specific resource")
        }
        val resources = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw IOException("Server returned invalid resource at index $index")
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                val sha256 = item.optString("sha256").trim().lowercase()
                val size = item.optLong("size", 0L)
                val route = item.optString("route").trim()
                val routeName = item.optString("routeName").trim().ifBlank { route }
                val sessionId = item.optString("downloadSessionId").trim()
                val attemptId = item.optString("downloadAttemptId").trim()
                val hasMoreRoutes = item.optBoolean("hasMoreRoutes", false)
                if (name.isEmpty() || url.isEmpty() ||
                    route.isEmpty() || !STATE_ID_PATTERN.matches(sessionId) || !STATE_ID_PATTERN.matches(attemptId) ||
                    !SHA256_PATTERN.matches(sha256) || size !in 1..MAX_RESOURCE_BYTES
                ) {
                    throw IOException("Server returned invalid metadata for resource ${name.ifBlank { index.toString() }}")
                }
                if (!runCatching { java.net.URL(url).protocol.equals("https", ignoreCase = true) }.getOrDefault(false)) {
                    throw IOException("Resource $name URL must use HTTPS")
                }
                add(PayloadResource(name, url, sha256, size, route, routeName, sessionId, attemptId, hasMoreRoutes))
            }
        }
        return PayloadInfo(command, payloadId, suPath, resources)
    }

    // 与服务端使用相同规则：单条设备专属 shell 命令、UTF-8 最多 1024 字节，
    // 禁止 NUL、分号、&、|| 或换行；允许单个管道、重定向、引号及命令替换。
    private fun validateCommand(command: String): String {
        val cmd = command.trim()
        if (cmd.isEmpty() || cmd.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) return ""
        if (cmd.indexOf('\u0000') >= 0 || FORBIDDEN_COMMAND_PATTERN.containsMatchIn(cmd)) return ""
        return cmd
    }

    private fun validateSuPath(value: String): String {
        val path = value.trim()
        if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > MAX_SU_PATH_BYTES) return ""
        if (!SU_PATH_PATTERN.matches(path)) return ""
        if (path.split('/').any { it == "." || it == ".." }) return ""
        return path
    }

    // 通过 Stellar shell 执行命令，合并 stdout/stderr，并把输出和退出码作为同一个结果返回。
    @Suppress("DEPRECATION")
    private fun executeCommand(command: String): CommandExecution {
        val wrappedCommand = "$command 2>&1"
        val process = Stellar.newProcess(arrayOf("sh", "-c", wrappedCommand), null, "/")
        return try {
            // 预设中的非交互 sh 需要立即收到 EOF，否则会持续等待标准输入。
            process.outputStream.close()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            CommandExecution(out, process.waitFor())
        } finally {
            runCatching { process.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    private fun executeSuCommand(suPath: String, innerCommand: String): CommandExecution {
        require(validateSuPath(suPath) == suPath) { "Invalid su path" }
        return executeProcess(arrayOf(suPath, "-c", innerCommand))
    }

    @Suppress("DEPRECATION")
    private fun executeProcess(arguments: Array<String>): CommandExecution {
        val nullableArguments = Array<String?>(arguments.size) { index -> arguments[index] }
        val process = Stellar.newProcess(nullableArguments, null, "/")
        return try {
            process.outputStream.close()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val output = listOf(stdout, stderr)
                .filter(String::isNotBlank)
                .joinToString("\n")
            CommandExecution(output, process.waitFor())
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun updateStage(next: EscalationStage) {
        mainHandler.post { stage = next }
    }

    private fun log(message: String) {
        shell.appendOutput("[提权] $message")
    }

    private fun fail(stage: EscalationStage, reason: String) {
        mainHandler.post {
            this.stage = EscalationStage.Done
            this.result = EscalationResult.Failure(stage, reason)
        }
        shell.appendOutput("[提权失败][$stage] $reason", isError = true)
    }

    private fun showDeviceNotSupported(profile: DeviceProfile) {
        mainHandler.post {
            stage = EscalationStage.Done
            result = EscalationResult.DeviceNotSupported(profile)
        }
        shell.appendOutput("[提权] server has no compatible profile for current device", isError = true)
    }

    override fun close() {
        executor.shutdownNow()
        resourceDownloader.close()
    }

    private data class PayloadInfo(
        val command: String,
        val payloadId: String,
        val suPath: String,
        val resources: List<PayloadResource>,
    )

    private data class PreparedPayload(val file: File)

    private data class LocalPayloadRequest(
        val uri: Uri,
        val command: String,
    )

    private data class CommandExecution(
        val output: String,
        val exitCode: Int,
    )

    private class DeviceNotSupportedException(val profile: DeviceProfile) : IOException()

    private companion object {
        val FORBIDDEN_COMMAND_PATTERN = Regex("[;\\r\\n&]|\\|\\|")
        val SU_PATH_PATTERN = Regex("^/(?:[A-Za-z0-9_+@.-]+/)*[A-Za-z0-9_+@.-]+$")
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        val STATE_ID_PATTERN = Regex("^[a-f0-9]{24,64}$")
        val ROOT_UID_PATTERN = Regex("(?:^|\\s)uid=0(?:\\(root\\))?(?:\\s|\\z)")
        const val MAX_COMMAND_BYTES = 1024
        const val MAX_SU_PATH_BYTES = 256
        const val MAX_RESOURCE_ID_LENGTH = 192
        const val MAX_RESOURCE_BYTES = 512L * 1024L * 1024L
        const val LOCAL_PAYLOAD_SU_PATH = "/data/local/tmp/su"
        const val MAX_DOWNLOAD_ROUTE_ATTEMPTS = 32
        const val MAX_LOCAL_DISPLAY_NAME_LENGTH = 128
        const val LOCAL_COPY_BUFFER_BYTES = 64 * 1024
        const val LOCAL_PAYLOAD_CACHE_DIRECTORY = "local-payloads"
        const val LOCAL_PAYLOAD_PREFIX = "payload-"
        const val ROOT_CHECK_INNER_COMMAND = "id"
        const val KERNEL_SU_ACTIVATION_ASSET = "yhroot_ksu_activate.sh"
    }
}
