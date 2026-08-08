package `in`.hridayan.ashell.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.hridayan.ashell.R
import `in`.hridayan.ashell.shell.StartupVerification
import `in`.hridayan.ashell.shell.StartupVerificationResult
import kotlinx.coroutines.delay

// 首次启动公告页面：加载并确认后才允许进入本地 ADB 页面。
// 公告、版本号与频道授权状态均来自服务端签名的启动响应。
// 验证接口未配置、网络失败或签名失败时保持关闭，不能进入后续页面。
@Composable
fun AnnouncementScreen(
    result: StartupVerificationResult,
    localVersion: String,
    localVersionCode: Int,
    onContinue: (StartupVerification) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    val version: String
    val author: String
    val announcement: String
    val needUpdate: Boolean
    val verification: StartupVerification?
    val verificationFailed: Boolean
    when (result) {
        is StartupVerificationResult.Success -> {
            verification = result.verification
            version = verification.announcement.version.ifBlank {
                "versionCode ${verification.serverVersionCode}"
            }
            author = verification.announcement.author.ifBlank {
                stringResource(R.string.announcement_author_fallback)
            }
            announcement = verification.announcement.announcement.ifBlank {
                stringResource(R.string.announcement_default_text)
            }
            needUpdate = verification.serverVersionCode != localVersionCode
            verificationFailed = !needUpdate &&
                !verification.authorized &&
                !verification.channelVerificationRequired
        }

        StartupVerificationResult.EndpointNotConfigured -> {
            verification = null
            version = localVersion
            author = stringResource(R.string.announcement_author_fallback)
            announcement = stringResource(R.string.startup_verification_not_configured)
            needUpdate = false
            verificationFailed = true
        }

        is StartupVerificationResult.Failure -> {
            verification = null
            version = localVersion
            author = stringResource(R.string.announcement_author_fallback)
            announcement = result.reason
            needUpdate = false
            verificationFailed = true
        }
    }

    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val accent = if (needUpdate || verificationFailed) colors.error else colors.primary
    val requiresReadingCountdown = verification != null && !needUpdate && !verificationFailed
    var readingSecondsRemaining by remember(result) {
        mutableIntStateOf(if (requiresReadingCountdown) ANNOUNCEMENT_READING_SECONDS else 0)
    }

    LaunchedEffect(result, requiresReadingCountdown) {
        if (!requiresReadingCountdown) {
            readingSecondsRemaining = 0
            return@LaunchedEffect
        }
        readingSecondsRemaining = ANNOUNCEMENT_READING_SECONDS
        while (readingSecondsRemaining > 0) {
            delay(1_000)
            readingSecondsRemaining--
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandHeader(accent = accent)
            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                .weight(1f)
                    .animateContentSize(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                    ) {
                        Text(
                            text = stringResource(
                                if (needUpdate) R.string.update_badge
                                else if (verificationFailed) R.string.startup_verification_failed_badge
                                else R.string.announcement_badge,
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(
                                if (needUpdate) R.string.update_required_title
                                else if (verificationFailed) R.string.startup_verification_failed_title
                                else R.string.announcement_title,
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (needUpdate || verificationFailed) colors.error else colors.onSurface,
                        )
                        Text(
                            text = stringResource(
                                if (needUpdate) R.string.update_required_subtitle
                                else if (verificationFailed) R.string.startup_verification_failed_subtitle
                                else R.string.announcement_subtitle,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    if (needUpdate) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            InfoTile(
                                label = stringResource(R.string.local_version_label),
                                value = localVersion,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                            InfoTile(
                                label = stringResource(
                                    if (verificationFailed) R.string.local_version_label
                                    else R.string.announcement_version_label,
                                ),
                                value = version,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        InfoTile(
                            label = stringResource(R.string.announcement_author_label),
                            value = author,
                            accent = accent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            InfoTile(
                                label = stringResource(
                                    if (verificationFailed) R.string.local_version_label
                                    else R.string.announcement_version_label,
                                ),
                                value = version,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                            InfoTile(
                                label = stringResource(R.string.announcement_author_label),
                                value = author,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.announcement_content_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        SelectionContainer {
                            Text(
                                text = when {
                                    needUpdate -> stringResource(
                                        R.string.update_required_message,
                                        localVersion,
                                        version,
                                    )
                                    verificationFailed -> announcement.ifBlank {
                                        stringResource(R.string.startup_verification_failed_message)
                                    }
                                    else -> announcement
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (needUpdate) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = onExit,
                    ) {
                        Text(stringResource(R.string.exit_app), fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        ),
                        onClick = {
                            if (UPDATE_URL.isBlank()) {
                                Toast.makeText(
                                    context,
                                    R.string.update_link_not_configured,
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.update_now), fontWeight = FontWeight.Bold)
                    }
                }
            } else if (verificationFailed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = onExit,
                    ) {
                        Text(stringResource(R.string.exit_app), fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = onRetry,
                    ) {
                        Text(stringResource(R.string.retry_verification), fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                MotionButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = readingSecondsRemaining == 0,
                    onClick = { verification?.let(onContinue) },
                ) {
                    Text(
                        text = if (readingSecondsRemaining > 0) {
                            stringResource(
                                R.string.announcement_countdown,
                                readingSecondsRemaining,
                            )
                        } else {
                            stringResource(R.string.announcement_continue)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun AnnouncementLoadingScreen() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandHeader(accent = colors.primary)
            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                    ) {
                        Text(
                            text = stringResource(R.string.announcement_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.announcement_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.announcement_loading_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InfoTile(
                            label = stringResource(R.string.announcement_version_label),
                            value = "—",
                            accent = colors.primary,
                            modifier = Modifier.weight(1f),
                        )
                        InfoTile(
                            label = stringResource(R.string.announcement_author_label),
                            value = "—",
                            accent = colors.primary,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(
                        text = stringResource(R.string.announcement_content_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                            )
                            Text(
                                text = stringResource(R.string.announcement_loading),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = stringResource(R.string.announcement_security_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
                onClick = {},
            ) {
                Text(
                    text = stringResource(R.string.announcement_loading),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BrandHeader(accent: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.announcement_ready_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InfoTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            }
        }
    }
}

// 公告版本不一致时，“立即更新”跳转到项目更新主页
private const val UPDATE_URL = "https://yhyun.asia/"
private const val ANNOUNCEMENT_READING_SECONDS = 5
