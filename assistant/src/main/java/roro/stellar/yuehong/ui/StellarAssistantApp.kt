package roro.stellar.yuehong.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import roro.stellar.yuehong.ghostlock.GhostLockActivity

/**
 * 自主构建入口。
 *
 * 本 fork 不再依赖原项目的远程启动验证/频道授权服务。
 * 启动后直接进入原有本地工作台；真正的设备、Root、内核和执行状态检查
 * 仍由后续工作台/GhostLock 流程负责。
 */
@Composable
fun StellarAssistantApp() {
    StellarTheme {
        val context = LocalContext.current
        BackHandler { (context as? Activity)?.moveTaskToBack(true) }

        val activity = context as? Activity ?: return@StellarTheme
        val release = System.getProperty("os.version").orEmpty()

        val intent = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            isGhostLockKernel(release)
        ) {
            Intent(context, GhostLockActivity::class.java)
        } else {
            Intent().apply {
                setClassName(
                    context.packageName,
                    "roro.stellar.manager.ui.features.manager.ManagerActivity",
                )
                putExtra("route", "workspace")
            }
        }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            activity.startActivity(intent)
            activity.finish()
        }
    }
}

private fun isGhostLockKernel(release: String): Boolean =
    release.startsWith("6.6.") || release.startsWith("6.12.")
