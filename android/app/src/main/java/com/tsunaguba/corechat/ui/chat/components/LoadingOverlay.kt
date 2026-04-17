package com.tsunaguba.corechat.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tsunaguba.corechat.R
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.ui.theme.StatusOk
import com.tsunaguba.corechat.ui.theme.StatusWarn

/**
 * Full-screen modal shown while the AI engine is genuinely starting up (Initializing)
 * or pulling a model (Downloading). Covers the chat UI so users cannot mistake a
 * transient startup state for the Ready state.
 *
 * Mirrors the `LoadingScreen` pattern in the pocket-brain web reference (stepped
 * checklist + current-stage highlight + optional progress bar).
 */
@Composable
fun LoadingOverlay(
    status: AiModelStatus,
    modifier: Modifier = Modifier,
) {
    val stage = stageOf(status) ?: return
    val progress = (status as? AiModelStatus.Downloading)?.progress

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(subtitle = subtitleFor(status))
                if (progress != null) ProgressStrip(progress = progress)
                StepList(activeStage = stage)
                Text(
                    text = stringResource(R.string.loading_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class LoadingStage { Detect, Check, Download, Init, Done }

private fun stageOf(status: AiModelStatus): LoadingStage? = when (status) {
    is AiModelStatus.Downloading -> LoadingStage.Download
    AiModelStatus.Initializing -> LoadingStage.Check
    else -> null // overlay hides for Ready / Error / Unavailable
}

@Composable
private fun subtitleFor(status: AiModelStatus): String = when (status) {
    is AiModelStatus.Downloading -> {
        val pct = (status.progress * 100).toInt().coerceIn(0, 99)
        stringResource(R.string.loading_subtitle_downloading, pct)
    }
    else -> stringResource(R.string.loading_subtitle_checking)
}

@Composable
private fun Header(subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.5.dp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.loading_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressStrip(progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${(progress * 100).toInt().coerceIn(0, 100)}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepList(activeStage: LoadingStage) {
    val steps = listOf(
        LoadingStage.Detect to R.string.loading_step_detect,
        LoadingStage.Check to R.string.loading_step_check,
        LoadingStage.Download to R.string.loading_step_download,
        LoadingStage.Init to R.string.loading_step_init,
        LoadingStage.Done to R.string.loading_step_done,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { (stage, labelRes) ->
            val state = when {
                stage.ordinal < activeStage.ordinal -> StepState.Done
                stage == activeStage -> StepState.Current
                else -> StepState.Pending
            }
            StepRow(label = stringResource(labelRes), state = state)
        }
    }
}

private enum class StepState { Done, Current, Pending }

@Composable
private fun StepRow(label: String, state: StepState) {
    val dotColor: Color = when (state) {
        StepState.Done -> StatusOk
        StepState.Current -> StatusWarn
        StepState.Pending -> MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = when (state) {
        StepState.Done -> MaterialTheme.colorScheme.onSurface
        StepState.Current -> MaterialTheme.colorScheme.onSurface
        StepState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = if (state == StepState.Current) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
