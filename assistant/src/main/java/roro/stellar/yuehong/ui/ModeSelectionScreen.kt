package roro.stellar.yuehong.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.R

@Composable
internal fun ModeSelectionScreen(
    kernelRelease: String,
    ghostLockRuntimeAvailable: Boolean,
    onOpenGhostLock: () -> Unit,
    onOpenStellar: () -> Unit,
) {
    val isGhostLockKernelFamily = kernelRelease.startsWith("6.6.") || kernelRelease.startsWith("6.12.")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .padding(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.mode_selection_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.mode_selection_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModeCard(
                title = stringResource(R.string.mode_ghostlock_title),
                body = stringResource(R.string.mode_ghostlock_body),
                status = when {
                    !ghostLockRuntimeAvailable -> stringResource(R.string.mode_ghostlock_android_required)
                    isGhostLockKernelFamily -> stringResource(R.string.mode_ghostlock_kernel_match, kernelRelease)
                    else -> stringResource(R.string.mode_ghostlock_kernel_other, kernelRelease)
                },
                button = stringResource(R.string.mode_ghostlock_open),
                enabled = ghostLockRuntimeAvailable,
                onClick = onOpenGhostLock,
            )
            ModeCard(
                title = stringResource(R.string.mode_stellar_title),
                body = stringResource(R.string.mode_stellar_body),
                status = stringResource(R.string.mode_stellar_status),
                button = stringResource(R.string.mode_stellar_open),
                enabled = true,
                onClick = onOpenStellar,
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    body: String,
    status: String,
    button: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.padding(top = 2.dp))
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = onClick,
            ) {
                Text(text = button, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}