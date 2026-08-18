package roro.stellar.yuehong.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.StellarShellController
import roro.stellar.yuehong.shell.StellarStatus
import kotlinx.coroutines.delay

@Composable
fun StellarActivationScreen(controller: StellarShellController) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var autoRequestAttempted by rememberSaveable { mutableStateOf(false) }
    var requestInFlight by remember { mutableStateOf(false) }

    fun requestPermission() {
        controller.dismissPermissionRequiredNotice()
        requestInFlight = true
        controller.requestPermission()
    }

    fun startSelfActivation() {
        runCatching {
            context.startActivity(
                Intent().apply {
                    setClassName(
                        context.packageName,
                        "roro.stellar.manager.ui.features.manager.ManagerActivity",
                    )
                    putExtra("route", "starter")
                    putExtra("is_root", false)
                    putExtra("host", "127.0.0.1")
                    putExtra("port", 0)
                    putExtra("has_secure_settings", false)
                },
            )
        }
    }

    // 第一次进入权限页且服务可用时自动发起一次授权；拒绝后只允许用户主动重试，
    // 避免系统权限弹窗反复出现。
    LaunchedEffect(controller.status) {
        if (controller.status == StellarStatus.NeedsPermission && !autoRequestAttempted) {
            delay(520)
            autoRequestAttempted = true
            requestPermission()
        }
        if (controller.status != StellarStatus.NeedsPermission) requestInFlight = false
    }

    LaunchedEffect(controller.permissionRequiredNotice) {
        if (controller.permissionRequiredNotice) requestInFlight = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.shizuku_page_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 7.dp),
                text = stringResource(R.string.shizuku_page_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PermissionStatusLabel(status = controller.status)
                    AnimatedContent(
                        targetState = controller.status,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 3 }) togetherWith
                                (fadeOut(tween(180)) + slideOutVertically(tween(240)) { -it / 4 })
                        },
                        label = "permission-status-copy",
                    ) { status ->
                        PermissionStatusCopy(status = status)
                    }

                    AnimatedVisibility(
                        visible = controller.permissionRequiredNotice,
                        enter = fadeIn() + scaleIn(initialScale = 0.96f),
                        exit = fadeOut() + scaleOut(targetScale = 0.98f),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.error.copy(alpha = 0.42f)),
                        ) {
                            Text(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                text = stringResource(R.string.shizuku_permission_denied_inline),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    when (controller.status) {
                        StellarStatus.NeedsPermission -> MotionButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !requestInFlight,
                            onClick = ::requestPermission,
                        ) {
                            if (requestInFlight) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                )
                                Text(
                                    modifier = Modifier.padding(start = 10.dp),
                                    text = stringResource(R.string.requesting_permission),
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.grant_shizuku_permission),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        StellarStatus.Unavailable -> Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MotionButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = ::startSelfActivation,
                            ) {
                                Text(
                                    stringResource(R.string.start_stellar_self_activation),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            FilledTonalButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(17.dp),
                                onClick = controller::refreshStatus,
                            ) {
                                Text(stringResource(R.string.check_shizuku_again), fontWeight = FontWeight.Bold)
                            }
                        }

                        StellarStatus.Granted -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.entering_local_adb),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        StellarStatus.Checking -> Unit
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            PermissionFeatureRow(
                index = "01",
                title = stringResource(R.string.permission_feature_local_title),
                description = stringResource(R.string.permission_feature_local_body),
            )
            PermissionFeatureRow(
                index = "02",
                title = stringResource(R.string.permission_feature_control_title),
                description = stringResource(R.string.permission_feature_control_body),
            )
            PermissionFeatureRow(
                index = "03",
                title = stringResource(R.string.permission_feature_recover_title),
                description = stringResource(R.string.permission_feature_recover_body),
            )
        }
    }
}

@Composable
private fun PermissionStatusCopy(status: StellarStatus) {
    val title = when (status) {
        StellarStatus.Checking -> stringResource(R.string.shizuku_checking_title)
        StellarStatus.NeedsPermission -> stringResource(R.string.shizuku_authorize_title)
        StellarStatus.Granted -> stringResource(R.string.shizuku_authorized_title)
        StellarStatus.Unavailable -> stringResource(R.string.shizuku_not_running_title)
    }
    val body = when (status) {
        StellarStatus.Checking -> stringResource(R.string.shizuku_checking_body)
        StellarStatus.NeedsPermission -> stringResource(R.string.shizuku_authorize_body)
        StellarStatus.Granted -> stringResource(R.string.shizuku_authorized_body)
        StellarStatus.Unavailable -> stringResource(R.string.shizuku_not_running_body)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PermissionStatusLabel(status: StellarStatus) {
    val colors = MaterialTheme.colorScheme
    val accent = when (status) {
        StellarStatus.Unavailable -> colors.error
        else -> colors.primary
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            text = "Stellar",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

@Composable
private fun PermissionFeatureRow(
    index: String,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            ),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                text = index,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
