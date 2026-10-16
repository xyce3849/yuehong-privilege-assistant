package roro.stellar.yuehong.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import roro.stellar.yuehong.BuildConfig
import roro.stellar.yuehong.dfroot.DfRootActivity
import roro.stellar.yuehong.ghostlock.GhostLockActivity
import roro.stellar.yuehong.shell.HttpStartupVerificationApi
import roro.stellar.yuehong.shell.StartupVerification
import roro.stellar.yuehong.shell.StartupVerificationResult

private enum class AppPage {
    Announcement,
    ChannelVerification,
    NecessaryPermissions,
    ModeSelection,
}

/**
 * 启动验证旁路：作者原版会要求远程授权 / 频道验证才能进入 App。
 * 设为 true 时，从入口页直接跳过验证环节（仍保留权限申请页），
 * 使 App 可脱离作者服务器独立使用。改为 false 即恢复作者原版行为。
 */
private const val SKIP_STARTUP_VERIFICATION: Boolean = true

@Composable
fun StellarAssistantApp() {
    StellarTheme {
        val context = LocalContext.current
        BackHandler { (context as? Activity)?.moveTaskToBack(true) }
        val startupVerificationApi = remember(context.applicationContext) {
            HttpStartupVerificationApi(context.applicationContext)
        }
        var page by rememberSaveable {
            mutableStateOf(
                if (SKIP_STARTUP_VERIFICATION) {
                    if (NecessaryPermissions.allGranted(context)) AppPage.ModeSelection
                    else AppPage.NecessaryPermissions
                } else {
                    AppPage.Announcement
                },
            )
        }
        var startupRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }
        // GhostLock 入口常开：内核是否真正支持改由 GhostLockActivity 运行时校验
        // （5.x 内核支持 beta 开关在 GhostLock 页内，开启后运行时放行）。
        val ghostLockKernelAvailable = true
        val dfRootPlatformAvailable = remember {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

        fun openGhostLockMode() {
            val activity = context as? Activity ?: return
            activity.startActivity(Intent(context, GhostLockActivity::class.java))
        }

        fun openDfRootMode() {
            if (!dfRootPlatformAvailable) return
            val activity = context as? Activity ?: return
            activity.startActivity(Intent(context, DfRootActivity::class.java))
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

        fun openStellarOriginalMode() {
            val activity = context as? Activity ?: return
            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "roro.stellar.yuehong.activities.MainActivity",
                )
            }
            activity.startActivity(intent)
        }

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val direction = if (forward) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = tween(480, easing = FastOutSlowInEasing),
                    initialOffsetX = { width -> direction * width / 3 },
                ) + fadeIn(tween(320)) + scaleIn(tween(420), initialScale = 0.985f)
                val exit = slideOutHorizontally(
                    animationSpec = tween(420, easing = FastOutSlowInEasing),
                    targetOffsetX = { width -> -direction * width / 4 },
                ) + fadeOut(tween(260)) + scaleOut(tween(360), targetScale = 0.99f)
                enter togetherWith exit
            },
            label = "app-page-transition",
        ) { activePage ->
            when (activePage) {
                AppPage.Announcement -> AnnouncementGate(
                    api = startupVerificationApi,
                    refreshGeneration = startupRefreshGeneration,
                    onContinue = { verification ->
                        val authorized = SKIP_STARTUP_VERIFICATION || verification.authorized
                        if (!authorized) {
                            page = AppPage.ChannelVerification
                        } else if (NecessaryPermissions.allGranted(context)) {
                            page = AppPage.ModeSelection
                        } else {
                            page = AppPage.NecessaryPermissions
                        }
                    },
                    onRetry = { startupRefreshGeneration++ },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )

                AppPage.ChannelVerification -> ChannelVerificationScreen(
                    api = startupVerificationApi,
                    onVerified = {
                        page = if (NecessaryPermissions.allGranted(context)) {
                            AppPage.ModeSelection
                        } else {
                            AppPage.NecessaryPermissions
                        }
                    },
                    onStartupInvalidated = {
                        startupRefreshGeneration++
                        page = AppPage.Announcement
                    },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )

                AppPage.NecessaryPermissions -> NecessaryPermissionsScreen(
                    onGranted = { page = AppPage.ModeSelection },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )

                AppPage.ModeSelection -> ModeSelectionScreen(
                    ghostLockKernelAvailable = ghostLockKernelAvailable,
                    dfRootPlatformAvailable = dfRootPlatformAvailable,
                    onOpenGhostLock = ::openGhostLockMode,
                    onOpenDfRoot = ::openDfRootMode,
                    onOpenStellar = ::openStellarMode,
                    onOpenVivoWired = ::openVivoWiredMode,
                    onOpenStellarOriginal = ::openStellarOriginalMode,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementGate(
    api: HttpStartupVerificationApi,
    refreshGeneration: Int,
    onContinue: (StartupVerification) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    var verificationResult by remember { mutableStateOf<StartupVerificationResult?>(null) }

    LaunchedEffect(api, refreshGeneration) {
        verificationResult = null
        verificationResult = api.verifyStartup()
    }

    val result = verificationResult
    AnimatedContent(
        targetState = result,
        transitionSpec = {
            fadeIn(tween(260, easing = FastOutSlowInEasing)) togetherWith
                fadeOut(tween(180))
        },
        label = "announcement-loading-transition",
    ) { loadedResult ->
        if (loadedResult == null) {
            AnnouncementLoadingScreen()
        } else {
            AnnouncementScreen(
                result = loadedResult,
                localVersion = BuildConfig.VERSION_NAME,
                localVersionCode = BuildConfig.VERSION_CODE,
                    onContinue = onContinue,
                    onRetry = onRetry,
                    onExit = onExit,
            )
        }
    }
}
