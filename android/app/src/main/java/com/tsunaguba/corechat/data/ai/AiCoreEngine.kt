package com.tsunaguba.corechat.data.ai

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerateContentResponse
import com.google.ai.edge.aicore.GenerationConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * On-device Gemini Nano engine backed by AICore. Model creation is lazy; the download
 * (if needed) is driven via [DownloadCallback] so the UI can show progress.
 */
class AiCoreEngine(
    private val context: Context,
) : AiEngine {

    override val id: String = ENGINE_ID

    private val _status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
    val status: StateFlow<AiModelStatus> = _status.asStateFlow()

    private val modelMutex = Mutex()
    @Volatile private var model: GenerativeModel? = null

    /** Total bytes the current download is expected to produce (0 until started). */
    @Volatile private var downloadTotalBytes: Long = 0

    /**
     * Probe: instantiate the model and prepare the inference engine. If the device
     * lacks AICore support or the prepare call fails for any reason, we treat the
     * engine as unavailable so the provider can fall back to cloud.
     */
    override suspend fun isAvailable(): Boolean = runCatching {
        val m = ensureModel()
        // prepareInferenceEngine resolves the model asset (possibly triggering a download).
        // Wrap in a timeout so a stalled handshake (no DownloadCallback activity) does not
        // leave the status flow stuck in Initializing indefinitely.
        withTimeout(PREPARE_TIMEOUT_MS) { m.prepareInferenceEngine() }
        // Unconditionally transition to Ready on a successful probe. `compareAndSet`
        // from Initializing would leave a prior Error/Unavailable sticky across
        // retry() calls, defeating explicit user-triggered recovery.
        _status.value = AiModelStatus.Ready(AiEngineMode.OnDeviceAiCore)
        true
    }.getOrElse { t ->
        // Timeout or any other failure -> unavailable; caller can retry explicitly.
        val reason = when (t) {
            is TimeoutCancellationException -> "prepare-timeout"
            else -> t::class.simpleName ?: "prepare-failed"
        }
        // Preserve an in-progress Downloading state — the DownloadCallback is the
        // authoritative driver for it. Only demote to Unavailable if we're not
        // actively downloading; otherwise the download callback will eventually
        // surface Ready / Error on its own.
        if (_status.value !is AiModelStatus.Downloading) {
            // AICore's own failure modes (no Gemini Nano support, prepare timeout)
            // are distinct from cloud reasons; surface as Unknown so the fallback
            // reason from CloudGeminiEngine (if any) takes precedence at the
            // provider level.
            _status.value = AiModelStatus.Unavailable()
        }
        android.util.Log.w(TAG, "AiCore prepare failed: $reason", t)
        false
    }

    override fun stream(history: List<ChatMessage>, prompt: String): Flow<String> = flow {
        val m = ensureModel()
        val composed = composePrompt(history, prompt)
        val chunks: Flow<GenerateContentResponse> = m.generateContentStream(composed)
        emitAll(chunks.map { it.text.orEmpty() })
    }

    private suspend fun ensureModel(): GenerativeModel {
        model?.let { return it }
        return modelMutex.withLock {
            model ?: createModel().also { model = it }
        }
    }

    private fun createModel(): GenerativeModel {
        val cfg: GenerationConfig = generationConfig {
            // AICore requires an application Context to reach the system service.
            // Passing the hilt-provided ApplicationContext is mandatory; without it,
            // content generation fails at runtime.
            context = this@AiCoreEngine.context
            temperature = DEFAULT_TEMPERATURE
            topK = DEFAULT_TOP_K
            maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS
        }
        val callback = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) {
                downloadTotalBytes = bytesToDownload
                _status.value = AiModelStatus.Downloading(progress = 0f)
            }
            override fun onDownloadProgress(totalBytesDownloaded: Long) {
                val total = downloadTotalBytes
                val progress = if (total > 0) {
                    (totalBytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    // Total unknown; surface indeterminate progress as a small non-zero value.
                    INDETERMINATE_PROGRESS
                }
                _status.value = AiModelStatus.Downloading(progress = progress)
            }
            override fun onDownloadFailed(failureStatus: String, e: GenerativeAIException) {
                android.util.Log.w(TAG, "AICore model download failed: $failureStatus", e)
                _status.value = AiModelStatus.Error(reason = failureStatus)
            }
            override fun onDownloadCompleted() {
                _status.value = AiModelStatus.Ready(AiEngineMode.OnDeviceAiCore)
            }
        }
        return GenerativeModel(
            generationConfig = cfg,
            downloadConfig = DownloadConfig(callback),
        )
    }

    private fun composePrompt(history: List<ChatMessage>, prompt: String): String {
        if (history.isEmpty()) return prompt
        val sb = StringBuilder()
        history.takeLast(MAX_HISTORY_TURNS).forEach { m ->
            val label = if (m.role == Role.USER) "User" else "Assistant"
            sb.append(label).append(": ").append(m.content).append('\n')
        }
        sb.append("User: ").append(prompt).append('\n').append("Assistant: ")
        return sb.toString()
    }

    companion object {
        const val ENGINE_ID = "aicore"
        private const val TAG = "AiCoreEngine"
        private const val DEFAULT_TEMPERATURE = 0.2f
        private const val DEFAULT_TOP_K = 16
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 512
        private const val INDETERMINATE_PROGRESS = 0.05f

        /** Clip prompt history to avoid exceeding Nano's ~2k-token input window. */
        private const val MAX_HISTORY_TURNS = 16

        /** Upper bound on prepareInferenceEngine() — prevents indefinite hang on stalled network. */
        private const val PREPARE_TIMEOUT_MS = 60_000L
    }
}
