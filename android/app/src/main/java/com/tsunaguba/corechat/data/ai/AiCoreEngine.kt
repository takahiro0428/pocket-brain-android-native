package com.tsunaguba.corechat.data.ai

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerateContentResponse
import com.google.ai.edge.aicore.GenerationConfig
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device Gemini Nano engine backed by AICore. Construction is lazy because the SDK
 * performs model resolution on first use. Download progress is surfaced via [status].
 */
class AiCoreEngine(
    private val context: Context,
) : AiEngine {

    override val id: String = "aicore"

    private val _status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
    val status: StateFlow<AiModelStatus> = _status.asStateFlow()

    private val modelMutex = Mutex()
    @Volatile private var model: GenerativeModel? = null

    /**
     * Feature-probe the SDK without provoking a model download. Any failure is
     * treated as "unavailable" so the provider can fall back to cloud.
     */
    override suspend fun isAvailable(): Boolean = runCatching {
        ensureModel()
        true
    }.getOrElse {
        _status.value = AiModelStatus.Unavailable
        false
    }

    override fun stream(history: List<ChatMessage>, prompt: String): Flow<String> {
        val composed = composePrompt(history, prompt)
        val chunks: Flow<GenerateContentResponse> = requireModel().generateContentStream(composed)
        return chunks.map { it.text.orEmpty() }
    }

    private suspend fun ensureModel(): GenerativeModel {
        model?.let { return it }
        return modelMutex.withLock {
            model ?: createModel().also { model = it }
        }
    }

    private fun requireModel(): GenerativeModel =
        model ?: error("AiCoreEngine.stream called before isAvailable() — illegal state")

    private fun createModel(): GenerativeModel {
        val cfg: GenerationConfig = generationConfig {
            temperature = 0.2f
            topK = 16
            maxOutputTokens = 512
        }
        val callback = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) {
                _status.value = AiModelStatus.Downloading(0f)
            }
            override fun onDownloadProgress(totalBytesDownloaded: Long) {
                // Absolute byte progress; UI only needs a coarse indicator.
                _status.value = AiModelStatus.Downloading(
                    progress = (_status.value as? AiModelStatus.Downloading)?.progress
                        ?.coerceAtLeast(0.1f) ?: 0.1f
                )
            }
            override fun onDownloadFailed(failureStatus: String, e: Exception) {
                _status.value = AiModelStatus.Error(reason = failureStatus)
            }
            override fun onDownloadCompleted() {
                _status.value = AiModelStatus.Ready
            }
        }
        val created = GenerativeModel(
            generationConfig = cfg,
            downloadConfig = DownloadConfig(callback),
        )
        // If the device already has the model cached, no callback fires — treat as Ready.
        _status.compareAndSet(AiModelStatus.Initializing, AiModelStatus.Ready)
        return created
    }

    private fun composePrompt(history: List<ChatMessage>, prompt: String): String {
        if (history.isEmpty()) return prompt
        val sb = StringBuilder()
        history.forEach { m ->
            val label = if (m.role == Role.USER) "User" else "Assistant"
            sb.append(label).append(": ").append(m.content).append('\n')
        }
        sb.append("User: ").append(prompt).append('\n').append("Assistant: ")
        return sb.toString()
    }
}
