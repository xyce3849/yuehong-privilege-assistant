package roro.stellar.yuehong.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.BuildConfig
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.ChannelVerificationResult
import roro.stellar.yuehong.shell.HttpStartupVerificationApi
import roro.stellar.yuehong.shell.StartupVerificationResult
import kotlinx.coroutines.delay

private sealed interface ChannelUiState {
    data object Loading : ChannelUiState
    data object Verified : ChannelUiState
    data class Pending(val code: String, val expireInSeconds: Int) : ChannelUiState
    data class Failure(val message: String) : ChannelUiState
}

@Composable
fun ChannelVerificationScreen(
    api: HttpStartupVerificationApi,
    onVerified: () -> Unit,
    onStartupInvalidated: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val channelConfirmationFailed = stringResource(R.string.channel_confirmation_failed)
    val startupNotConfigured = stringResource(R.string.startup_verification_not_configured)
    var requestGeneration by rememberSaveable { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ChannelUiState>(ChannelUiState.Loading) }

    LaunchedEffect(requestGeneration) {
        state = ChannelUiState.Loading
        when (val channelResult = api.requestChannelAuthorization()) {
            ChannelVerificationResult.Authorized -> {
                when (val startup = api.verifyStartup()) {
                    is StartupVerificationResult.Success -> {
                        val verification = startup.verification
                        when {
                            verification.serverVersionCode != BuildConfig.VERSION_CODE -> {
                                onStartupInvalidated()
                            }
                            verification.authorized -> {
                                state = ChannelUiState.Verified
                                delay(650)
                                onVerified()
                            }
                            else -> state = ChannelUiState.Failure(
                                channelConfirmationFailed,
                            )
                        }
                    }
                    StartupVerificationResult.EndpointNotConfigured -> {
                        state = ChannelUiState.Failure(
                            startupNotConfigured,
                        )
                    }
                    is StartupVerificationResult.Failure -> {
                        state = ChannelUiState.Failure(startup.reason)
                    }
                }
            }
            is ChannelVerificationResult.Pending -> {
                state = ChannelUiState.Pending(
                    code = channelResult.verifyCode,
                    expireInSeconds = channelResult.expireInSeconds,
                )
            }
            is ChannelVerificationResult.Failure -> {
                state = ChannelUiState.Failure(channelResult.reason)
            }
        }
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
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.channel_page_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 7.dp),
                text = stringResource(R.string.channel_page_subtitle),
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
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 4 }) togetherWith
                            (fadeOut(tween(180)) + slideOutVertically(tween(240)) { -it / 5 })
                    },
                    label = "channel-verification-state",
                ) { activeState ->
                    ChannelStateContent(
                        state = activeState,
                        onCopyCode = { code -> copyVerificationCode(context, code) },
                        onOpenChannel = { openChannel(context) },
                        onRetry = { requestGeneration++ },
                        onExit = onExit,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.channel_security_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChannelStateContent(
    state: ChannelUiState,
    onCopyCode: (String) -> Unit,
    onOpenChannel: () -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                text = stringResource(R.string.channel_status_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (state is ChannelUiState.Failure) colors.error else colors.primary,
            )
        }

        when (state) {
            ChannelUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                Text(
                    text = stringResource(R.string.channel_checking),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.channel_checking_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            ChannelUiState.Verified -> {
                CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                Text(
                    text = stringResource(R.string.channel_verified_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.channel_verified_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            is ChannelUiState.Pending -> {
                Text(
                    text = stringResource(R.string.channel_pending_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.channel_pending_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.55f)),
                ) {
                    SelectionContainer {
                        Text(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                            text = state.code,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.channel_code_expiry,
                        ((state.expireInSeconds + 59) / 60).coerceAtLeast(1),
                        ((state.expireInSeconds + 59) / 60).coerceAtLeast(1),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    onClick = { onCopyCode(state.code) },
                ) {
                    Text(stringResource(R.string.copy_verification_code), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    onClick = {
                        onCopyCode(state.code)
                        onOpenChannel()
                    },
                ) {
                    Text(stringResource(R.string.open_qq_channel), fontWeight = FontWeight.Bold)
                }
                MotionButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRetry,
                ) {
                    Text(stringResource(R.string.channel_recheck), fontWeight = FontWeight.Bold)
                }
            }

            is ChannelUiState.Failure -> {
                Text(
                    text = stringResource(R.string.channel_failed_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                        onClick = onExit,
                    ) {
                        Text(stringResource(R.string.exit_app), fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                        onClick = onRetry,
                    ) {
                        Text(stringResource(R.string.retry_verification), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun copyVerificationCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DMKPZ verification code", code))
    Toast.makeText(context, R.string.verification_code_copied, Toast.LENGTH_SHORT).show()
}

private fun openChannel(context: Context) {
    val url = BuildConfig.SERVER_CHANNEL_JOIN_URL.trim()
    if (url.isEmpty()) {
        Toast.makeText(context, R.string.channel_link_not_configured, Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, R.string.channel_open_failed, Toast.LENGTH_LONG).show()
    }
}
