package com.tsunaguba.corechat.data.ai

import com.tsunaguba.corechat.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a streaming chat AI engine. Two concrete implementations exist:
 * on-device AICore (Gemini Nano) and cloud Gemini.
 *
 * [isAvailable] must be cheap and safe to call repeatedly; it's used by the provider
 * to decide fallback routing.
 */
interface AiEngine {
    /** Stable identifier used for logging / telemetry. */
    val id: String

    /** Lightweight availability probe. Implementations must not throw. */
    suspend fun isAvailable(): Boolean

    /**
     * Stream assistant response text deltas for [prompt] with the given [history].
     * The flow terminates when the model finishes generation. Errors are propagated
     * via flow exception — the caller is responsible for fallback decisions.
     */
    fun stream(history: List<ChatMessage>, prompt: String): Flow<String>
}
