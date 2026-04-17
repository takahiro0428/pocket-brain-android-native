package com.tsunaguba.corechat.data.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.generationConfig
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

/**
 * Cloud Gemini engine used as fallback when AICore is unavailable.
 * API key is injected via BuildConfig.GEMINI_API_KEY (sourced from local.properties or CI secret).
 *
 * ### Availability probe
 * `isAvailable()` now performs a short `countTokens` round-trip (bounded by
 * [CLOUD_PROBE_TIMEOUT_MS]) so that an invalid/expired API key or unreachable
 * network is detected at startup and surfaced as `Unavailable`, instead of the
 * engine masquerading as ready until the first send fails.
 */
class CloudGeminiEngine(
    private val apiKey: String,
    modelName: String = DEFAULT_MODEL,
    // Injection seam for unit tests. Default calls the real countTokens.
    private val probe: suspend (GenerativeModel) -> Unit = DEFAULT_PROBE,
) : AiEngine {

    override val id: String = "cloud-gemini"

    private val model: GenerativeModel? = if (apiKey.isNotBlank()) {
        GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 512
            },
        )
    } else {
        null
    }

    override suspend fun isAvailable(): Boolean {
        val m = model ?: return false
        return runCatching {
            withTimeout(CLOUD_PROBE_TIMEOUT_MS) { probe(m) }
        }.fold(
            onSuccess = { true },
            onFailure = { t ->
                val reason = when (t) {
                    is TimeoutCancellationException -> "probe-timeout"
                    else -> t::class.simpleName ?: "probe-failed"
                }
                android.util.Log.w(TAG, "Cloud probe failed: $reason", t)
                false
            },
        )
    }

    override fun stream(history: List<ChatMessage>, prompt: String): Flow<String> {
        val m = model ?: error("CloudGeminiEngine has no API key configured")
        val chat = m.startChat(history = history.toContentList())
        return chat.sendMessageStream(prompt).map { it.text.orEmpty() }
    }

    private fun List<ChatMessage>.toContentList(): List<Content> = map { msg ->
        Content(
            role = if (msg.role == Role.USER) "user" else "model",
            parts = listOf(TextPart(msg.content)),
        )
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-1.5-flash"
        private const val TAG = "CloudGeminiEngine"

        /**
         * Upper bound on the startup availability probe. Keeps install-and-launch snappy
         * while still allowing a round-trip to validate the API key and reachability.
         */
        const val CLOUD_PROBE_TIMEOUT_MS: Long = 4_000L

        /**
         * `countTokens` is the cheapest auth-validating Gemini call: it round-trips the
         * key and network without invoking generation (no billing for completion tokens).
         */
        private val DEFAULT_PROBE: suspend (GenerativeModel) -> Unit = { m ->
            m.countTokens(
                Content(role = "user", parts = listOf(TextPart("ping"))),
            )
        }
    }
}
