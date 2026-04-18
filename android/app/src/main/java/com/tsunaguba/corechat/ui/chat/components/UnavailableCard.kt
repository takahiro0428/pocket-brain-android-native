package com.tsunaguba.corechat.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tsunaguba.corechat.BuildConfig
import com.tsunaguba.corechat.R
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.UnavailableReason

/**
 * Centered card shown on the chat screen when the engine is Unavailable or Error,
 * so the user sees a clear terminal state with a retry action instead of an
 * ambiguous empty screen. Mirrors pocket-brain's `SetupCard` retry pattern.
 *
 * The body message is chosen from [AiModelStatus.Unavailable.reason] so testers
 * can self-diagnose "APIキー未設定" vs "ネットワーク不通" instead of the old
 * generic "ネットワークまたは API キーを確認…" catch-all.
 */
@Composable
fun UnavailableCard(
    status: AiModelStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(max = 360.dp)
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.unavailable_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(bodyFor(status)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.action_retry_full))
            }
            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                DebugDiagnosticsBlock(status)
            }
        }
    }
}

private fun bodyFor(status: AiModelStatus): Int {
    // Error state comes from a failed stream(), not from the availability probe, so
    // the remediation hint is different ("送信に失敗…" rather than "APIキーを…").
    if (status is AiModelStatus.Error) return R.string.error_send_failed
    // Add new UnavailableReason values to this when (no `else` branch on purpose so
    // future enum additions produce a compiler warning here).
    return when ((status as? AiModelStatus.Unavailable)?.reason) {
        UnavailableReason.ApiKeyMissing -> R.string.unavailable_reason_api_key_missing
        UnavailableReason.ApiKeyMalformed -> R.string.unavailable_reason_api_key_malformed
        UnavailableReason.ApiKeyRejected -> R.string.unavailable_reason_api_key_rejected
        UnavailableReason.NetworkUnreachable -> R.string.unavailable_reason_network
        UnavailableReason.ProbeTimeout -> R.string.unavailable_reason_timeout
        UnavailableReason.Unknown, null -> R.string.unavailable_card_body
    }
}

/**
 * Visible only in debug builds. Prints the baked-in API key length, its SHA-256
 * prefix, and the last probe reason so the tester can distinguish "Secret was
 * empty at build time" from "Secret injected but cloud rejected it".
 */
@Composable
private fun DebugDiagnosticsBlock(status: AiModelStatus) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.debug_diagnostics_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val keyLen = BuildConfig.GEMINI_API_KEY_LENGTH
        val keyHash = BuildConfig.GEMINI_API_KEY_SHA256_PREFIX
        if (keyLen == 0) {
            Text(
                text = stringResource(R.string.debug_diagnostics_key_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.debug_diagnostics_key_length, keyLen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.debug_diagnostics_key_hash, keyHash),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val reasonLabel = (status as? AiModelStatus.Unavailable)?.reason?.name ?: "-"
        Text(
            text = stringResource(R.string.debug_diagnostics_probe_reason, reasonLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
