package com.tsunaguba.corechat.domain.repository

import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    /** Hot state of the underlying AI engine. */
    val status: StateFlow<AiModelStatus>

    /**
     * Stream assistant response text chunks for [prompt] given conversation [history].
     * Emits incremental deltas; the caller is responsible for concatenating.
     */
    fun sendMessage(history: List<ChatMessage>, prompt: String): Flow<String>
}
