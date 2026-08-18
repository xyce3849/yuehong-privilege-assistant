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
import roro.stellar.yuehong.ghostlock.GhostLockActivity
import roro.stellar.yuehong.shell.HttpStartupVerificationApi
import roro.stellar.yuehong.shell.StartupVerification
import roro.stellar.yuehong.shell.StartupVerificationResult

private enum class AppPage {
    Announcement,
    ChannelVerification,
}

@Composable
fun StellarAssistantApp() {
    StellarTheme {
        val context = LocalContext.current
        BackHandler { (context as? Activity)?.moveTaskToBack(true) }
        val startupVerificationApi = remember(context.applicationContext) {
            HttpStartupVerificationApi(context.applicationContext)
        }
        var page by rememberSaveable { mutableStateOf(AppPage.Announcement) }
        var startupRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }

        fun openKernelMode() {
            val release = System.getProperty("os.version").orEmpty()
            val activity = context as? Activity ?: return
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
                        if (verification.authorized) openKernelMode()
                        else page = AppPage.ChannelVerification
                    },
                    onRetry = { startupRefreshGeneration++ },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )

                AppPage.ChannelVerification -> ChannelVerificationScreen(
                    api = startupVerificationApi,
                    onVerified = ::openKernelMode,
                    onStartupInvalidated = {
                        startupRefreshGeneration++
                        page = AppPage.Announcement
                    },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )
            }
        }
    }
}

private fun isGhostLockKernel(release: String): Boolean =
    release.startsWith("6.6.") || release.startsWith("6.12.")

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
                onLoadSupportedModels = api::loadSupportedModels,
                onRetry = onRetry,
                onExit = onExit,
            )
        }
    }
}
