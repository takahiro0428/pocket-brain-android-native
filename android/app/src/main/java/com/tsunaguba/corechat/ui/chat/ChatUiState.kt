package com.tsunaguba.corechat.ui.chat

import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val status: AiModelStatus = AiModelStatus.Initializing,
    val isSending: Boolean = false,
    val transientError: String? = null,
)
