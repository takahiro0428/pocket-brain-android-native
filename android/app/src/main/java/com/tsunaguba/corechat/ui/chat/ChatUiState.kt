package com.tsunaguba.corechat.ui.chat

import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val status: AiModelStatus = AiModelStatus.Initializing,
    val isSending: Boolean = false,
    /**
     * Flag indicating the last send attempt failed. The UI resolves the
     * localized message via `R.string.error_send_failed`; keeping a flag here
     * avoids hardcoding user-visible strings in the ViewModel.
     */
    val sendFailed: Boolean = false,
)
