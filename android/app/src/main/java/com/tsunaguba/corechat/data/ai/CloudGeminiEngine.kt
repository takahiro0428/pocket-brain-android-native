package com.tsunaguba.corechat.data.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.generationConfig
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import com.tsunaguba.corechat.domain.model.UnavailableReason
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
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

    /**
     * Reason classification from the most recent [isAvailable] call. `null` means
     * either the probe succeeded or was never attempted. Consumers (e.g.
     * [AiEngineProvider]) read this right after [isAvailable] returns so the UI
     * can show a specific remediation hint.
     */
    @Volatile
    var lastUnavailableReason: UnavailableReason? = null
        private set

    override suspend fun isAvailable(): Boolean {
        if (apiKey.isBlank()) {
            lastUnavailableReason = UnavailableReason.ApiKeyMissing
            return false
        }
        if (apiKey.length < MIN_PLAUSIBLE_KEY_LENGTH) {
            // ~39 chars is the standard AI Studio key length; anything substantially
            // shorter usually means the Secret got trimmed or quote-wrapped on paste.
            lastUnavailableReason = UnavailableReason.ApiKeyMalformed
            return false
        }
        val m = model ?: run {
            lastUnavailableReason = UnavailableReason.ApiKeyMissing
            return false
        }
        return try {
            withTimeout(CLOUD_PROBE_TIMEOUT_MS) { probe(m) }
            lastUnavailableReason = null
            true
        } catch (t: Throwable) {
            // TimeoutCancellationException is a CancellationException subtype but we treat
            // it as a probe outcome, not upstream cancellation. Any *other* cancellation
            // (scope cancel, parent coroutine cancel) must propagate — swallowing it here
            // would cause caller coroutines to continue running after cancellation.
            if (t is CancellationException && t !is TimeoutCancellationException) throw t
            val reason = classify(t)
            lastUnavailableReason = reason
            android.util.Log.w(TAG, "Cloud probe failed: $reason", t)
            false
        }
    }

    private fun classify(t: Throwable): UnavailableReason {
        if (t is TimeoutCancellationException) return UnavailableReason.ProbeTimeout
        if (t is UnknownHostException) return UnavailableReason.NetworkUnreachable
        if (t is SocketTimeoutException) return UnavailableReason.NetworkUnreachable
        if (t is SSLException) return UnavailableReason.NetworkUnreachable

        // Check error messages at word boundaries so "request id 4018…" or "took 401ms"
        // don't false-positive into ApiKeyRejected. The `in` + Regex approach avoids
        // the naive `contains("401")` substring trap flagged in code review.
        val msg = t.message.orEmpty()
        if (HTTP_AUTH_CODE_REGEX.containsMatchIn(msg)) return UnavailableReason.ApiKeyRejected
        if (AUTH_KEYWORD_REGEX.containsMatchIn(msg)) return UnavailableReason.ApiKeyRejected

        // Class-name match (SDK-specific). proguard-rules.pro keeps the generativeai
        // package's class names intact, so this is stable in release; the message
        // fallbacks above catch cases where the key-rejection error is wrapped in
        // a generic exception type we don't specifically recognise.
        val className = t::class.simpleName.orEmpty()
        if (className.contains("PermissionDenied", ignoreCase = true)) return UnavailableReason.ApiKeyRejected
        if (className.contains("Unauthenticated", ignoreCase = true)) return UnavailableReason.ApiKeyRejected
        if (className.contains("InvalidApiKey", ignoreCase = true)) return UnavailableReason.ApiKeyRejected

        return UnavailableReason.Unknown
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
         * Below this length the key is almost certainly malformed (standard AI Studio
         * keys are ~39 chars). Set generously — a false-positive here would incorrectly
         * classify a real-but-strange key as malformed and hide a genuine auth failure.
         */
        const val MIN_PLAUSIBLE_KEY_LENGTH = 20

        /** HTTP auth codes at word boundaries. Guards against "request id 4018…" etc. */
        private val HTTP_AUTH_CODE_REGEX = Regex("""\b(401|403)\b""")

        /**
         * Phrases that strongly imply an authentication/authorization failure rather
         * than a quota/content/network problem. Keep narrow to avoid misclassifying
         * generic Gemini API errors (which tend to contain "Api" or "key" in unrelated
         * contexts) as ApiKeyRejected.
         */
        private val AUTH_KEYWORD_REGEX = Regex(
            """(?i)\b(invalid api key|api key not valid|unauthenticated|permission denied|unauthorized|forbidden)\b""",
        )

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
