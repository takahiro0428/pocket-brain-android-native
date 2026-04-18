package com.tsunaguba.corechat.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tsunaguba.corechat.R
import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.ui.theme.StatusError
import com.tsunaguba.corechat.ui.theme.StatusOk
import com.tsunaguba.corechat.ui.theme.StatusWarn

@Composable
fun StatusBar(
    status: AiModelStatus,
    modifier: Modifier = Modifier,
) {
    val (label: String, dot: Color) = when (status) {
        is AiModelStatus.Ready -> when (status.mode) {
            AiEngineMode.OnDeviceAiCore -> stringResource(R.string.status_ready_ondevice) to StatusOk
            AiEngineMode.OnDeviceMediaPipe -> stringResource(R.string.status_ready_mediapipe) to StatusOk
            AiEngineMode.Cloud -> stringResource(R.string.status_ready_cloud) to StatusOk
        }
        is AiModelStatus.Downloading -> {
            val pct = (status.progress * 100).toInt().coerceIn(0, 99)
            stringResource(R.string.status_downloading, pct) to StatusWarn
        }
        AiModelStatus.Initializing -> stringResource(R.string.status_initializing) to StatusWarn
        // The status pill stays generic ("AI利用不可"); UnavailableCard is the surface
        // that renders the specific reason so the pill doesn't get truncated on
        // long translations.
        is AiModelStatus.Unavailable -> stringResource(R.string.status_unavailable) to StatusError
        // NOTE: status.reason is intentionally not shown to the user — it may contain
        // low-level SDK exception strings / device identifiers. Reason is only logged.
        is AiModelStatus.Error -> stringResource(R.string.status_error) to StatusError
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape).background(dot),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
