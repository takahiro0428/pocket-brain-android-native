package com.tsunaguba.corechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsunaguba.corechat.R
import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.canSend
import com.tsunaguba.corechat.ui.chat.components.ErrorBanner
import com.tsunaguba.corechat.ui.chat.components.InputBar
import com.tsunaguba.corechat.ui.chat.components.LoadingOverlay
import com.tsunaguba.corechat.ui.chat.components.MessageBubble
import com.tsunaguba.corechat.ui.chat.components.StatusBar
import com.tsunaguba.corechat.ui.chat.components.UnavailableCard

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-scroll to latest message whenever the list grows or the last message content grows.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    val retryLabel = stringResource(R.string.action_retry)
    val sendFailedMessage = stringResource(R.string.error_send_failed)
    LaunchedEffect(state.sendFailed) {
        if (state.sendFailed) {
            val result = snackbarHostState.showSnackbar(
                message = sendFailedMessage,
                actionLabel = retryLabel,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.retry()
            }
            viewModel.clearError()
        }
    }

    val terminalError = state.status is AiModelStatus.Unavailable ||
        state.status is AiModelStatus.Error

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                StatusBar(status = state.status)
                // When terminal error coexists with prior conversation, surface a retry
                // affordance without hiding the chat history. When there are no messages,
                // the centered UnavailableCard already provides the retry action.
                if (terminalError && state.messages.isNotEmpty()) {
                    ErrorBanner(onRetry = viewModel::retry)
                }
            }
        },
        bottomBar = {
            InputBar(
                enabled = state.status.canSend() && !state.isSending,
                onSend = viewModel::send,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner: PaddingValues ->
        Box(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            when {
                state.messages.isEmpty() && terminalError -> UnavailableCard(
                    status = state.status,
                    onRetry = viewModel::retry,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.messages.isEmpty() -> EmptyState(
                    status = state.status,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items = state.messages, key = { it.id }) { msg ->
                        MessageBubble(message = msg)
                    }
                }
            }
        }
    }

    // Modal overlay during Initializing / Downloading. Dialog renders above the
    // Scaffold so users can never mistake a startup state for the Ready state.
    LoadingOverlay(status = state.status)
}

@Composable
private fun EmptyState(status: AiModelStatus, modifier: Modifier = Modifier) {
    // Subtitle is mode-aware to avoid claiming "端末内で完結" while actually routing
    // through the cloud — doing so would mislead users about where their messages go.
    val subtitleRes = when {
        status is AiModelStatus.Ready && status.mode == AiEngineMode.OnDeviceAiCore ->
            R.string.empty_state_subtitle_ondevice
        status is AiModelStatus.Ready && status.mode == AiEngineMode.OnDeviceMediaPipe ->
            R.string.empty_state_subtitle_ondevice
        status is AiModelStatus.Ready && status.mode == AiEngineMode.Cloud ->
            R.string.empty_state_subtitle_cloud
        else -> R.string.empty_state_subtitle_default
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.empty_state_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(subtitleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
