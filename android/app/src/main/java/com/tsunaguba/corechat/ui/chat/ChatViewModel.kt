package com.tsunaguba.corechat.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import com.tsunaguba.corechat.domain.model.canSend
import com.tsunaguba.corechat.domain.usecase.ObserveModelStatusUseCase
import com.tsunaguba.corechat.domain.usecase.RetryEngineUseCase
import com.tsunaguba.corechat.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessage: SendMessageUseCase,
    private val retryEngine: RetryEngineUseCase,
    observeStatus: ObserveModelStatusUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    init {
        observeStatus()
            .onEach { s -> _uiState.value = _uiState.value.copy(status = s) }
            .launchIn(viewModelScope)
    }

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || _uiState.value.isSending) return
        // Defense in depth: the input bar should already be disabled unless status
        // is Ready, but guard here so programmatic or race-y invocations can't bypass.
        if (!_uiState.value.status.canSend()) return

        val userMsg = ChatMessage(role = Role.USER, content = trimmed)
        val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "", isStreaming = true)

        val history = _uiState.value.messages
        _uiState.value = _uiState.value.copy(
            messages = history + userMsg + assistantMsg,
            isSending = true,
            sendFailed = false,
        )

        activeJob = viewModelScope.launch {
            sendMessage(history, trimmed)
                .catch { e ->
                    // Never surface raw SDK error strings to the user (may contain
                    // device identifiers / stack fragments). Log in detail; the UI
                    // resolves a localized generic message from the `sendFailed` flag.
                    Log.w(TAG, "sendMessage failed", e)
                    _uiState.value = _uiState.value.copy(
                        sendFailed = true,
                        messages = _uiState.value.messages
                            .updateAssistant(assistantMsg.id) { copy(isStreaming = false) },
                    )
                }
                .onCompletion {
                    val snapshot = _uiState.value
                    val current = snapshot.messages.firstOrNull { it.id == assistantMsg.id }
                    val updatedMessages = if (current != null && current.content.isEmpty()) {
                        // Drop the placeholder assistant bubble if no chunks arrived
                        // (e.g. screen closed, early cancellation, engine returned nothing).
                        snapshot.messages.filterNot { it.id == assistantMsg.id }
                    } else {
                        snapshot.messages.updateAssistant(assistantMsg.id) {
                            copy(isStreaming = false)
                        }
                    }
                    _uiState.value = snapshot.copy(isSending = false, messages = updatedMessages)
                }
                .collect { chunk ->
                    if (chunk.isEmpty()) return@collect
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages
                            .updateAssistant(assistantMsg.id) {
                                copy(content = content + chunk)
                            },
                    )
                }
        }
    }

    fun clearError() {
        if (_uiState.value.sendFailed) {
            _uiState.value = _uiState.value.copy(sendFailed = false)
        }
    }

    fun retry() {
        viewModelScope.launch { retryEngine() }
    }

    private inline fun List<ChatMessage>.updateAssistant(
        id: String,
        transform: ChatMessage.() -> ChatMessage,
    ): List<ChatMessage> = map { if (it.id == id) it.transform() else it }

    private companion object {
        private const val TAG = "ChatViewModel"
    }
}
