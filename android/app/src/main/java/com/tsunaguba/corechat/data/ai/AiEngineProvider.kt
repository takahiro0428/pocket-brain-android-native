package com.tsunaguba.corechat.data.ai

import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Routes requests to the best available engine:
 *   1. AICore (on-device) if the device reports it available
 *   2. Cloud Gemini otherwise
 *
 * Also handles runtime fallback: if a stream from AICore fails mid-flight, we switch
 * to cloud (if available) for that request without losing the user's prompt.
 *
 * [status] is the effective status exposed to the UI. It mirrors AiCoreEngine.status
 * until we fall back, then flips to CloudFallback / Unavailable.
 */
class AiEngineProvider(
    private val aicore: AiCoreEngine,
    private val cloud: CloudGeminiEngine,
    externalScope: CoroutineScope,
) {

    private val _status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
    val status: StateFlow<AiModelStatus> = _status.asStateFlow()

    @Volatile private var useCloud: Boolean = false

    init {
        // Mirror AICore status into the effective status until we hard-fallback.
        aicore.status
            .onEach { s -> if (!useCloud) _status.value = s }
            .launchIn(externalScope)

        externalScope.launch {
            val aicoreOk = aicore.isAvailable()
            if (!aicoreOk) {
                val cloudOk = cloud.isAvailable()
                useCloud = cloudOk
                _status.value = if (cloudOk) AiModelStatus.CloudFallback else AiModelStatus.Unavailable
            }
        }
    }

    fun stream(history: List<ChatMessage>, prompt: String): Flow<String> {
        val primary = if (useCloud) cloud else aicore
        return primary.stream(history, prompt)
            .catch { error ->
                // AICore failed mid-stream — try cloud as graceful degradation.
                if (primary.id == aicore.id && cloud.isAvailable()) {
                    useCloud = true
                    _status.value = AiModelStatus.CloudFallback
                    emitAll(cloud.stream(history, prompt))
                } else {
                    _status.value = AiModelStatus.Error(error.message ?: "stream failed")
                    throw error
                }
            }
    }
}
