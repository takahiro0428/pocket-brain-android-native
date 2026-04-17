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
     *
     * Contract:
     * - [history] must NOT include the current [prompt] or a placeholder assistant message.
     * - Each emitted string is a delta, not a cumulative snapshot.
     * - Errors are propagated via the flow. `CancellationException` indicates cooperative
     *   cancellation (the collector stopped) and should not be treated as an error.
     */
    fun sendMessage(history: List<ChatMessage>, prompt: String): Flow<String>
}
