package roro.stellar.yuehong.ui

import android.app.Activity
import android.content.Intent
import android.system.Os
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import roro.stellar.yuehong.ghostlock.GhostLockActivity

@Composable
fun StellarAssistantApp() {
    StellarTheme {
        val context = LocalContext.current
        BackHandler { (context as? Activity)?.moveTaskToBack(true) }
        val kernelRelease = remember {
            runCatching { Os.uname().release }.getOrNull().orEmpty().ifBlank {
                System.getProperty("os.version", "unknown")
            }
        }
        val ghostLockKernelAvailable = remember(kernelRelease) {
            GHOSTLOCK_KERNEL_PATTERN.containsMatchIn(kernelRelease)
        }

        fun openGhostLockMode() {
            val activity = context as? Activity ?: return
            activity.startActivity(Intent(context, GhostLockActivity::class.java))
        }

        fun openStellarMode() {
            val activity = context as? Activity ?: return
            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "roro.stellar.manager.ui.features.manager.ManagerActivity",
                )
                putExtra("route", "workspace")
            }
            activity.startActivity(intent)
        }

        fun openVivoWiredMode() {
            val activity = context as? Activity ?: return
            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "roro.stellar.manager.ui.features.wired.VivoWiredActivity",
                )
            }
            activity.startActivity(intent)
        }

        ModeSelectionScreen(
            ghostLockKernelAvailable = ghostLockKernelAvailable,
            onOpenGhostLock = ::openGhostLockMode,
            onOpenStellar = ::openStellarMode,
            onOpenVivoWired = ::openVivoWiredMode,
        )
    }
}

private val GHOSTLOCK_KERNEL_PATTERN = Regex("^6\\.")
