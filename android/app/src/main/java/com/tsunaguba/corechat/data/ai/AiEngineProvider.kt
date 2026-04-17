package com.tsunaguba.corechat.data.ai

import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Routes requests to the best available engine:
 *   1. AICore (on-device) if the device reports it available
 *   2. Cloud Gemini otherwise
 *
 * Also handles runtime fallback: if an AICore stream fails mid-flight, we switch to
 * cloud (if available) for that request without losing the user's prompt.
 *
 * [status] is the effective status exposed to the UI. It mirrors AiCoreEngine.status
 * until a hard fallback, at which point it flips to CloudFallback / Unavailable.
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
        // Mirror AICore status into the effective status until hard-fallback.
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

    fun stream(history: List<ChatMessage>, prompt: String): Flow<String> = flow {
        val primary = if (useCloud) cloud else aicore
        try {
            emitAll(primary.stream(history, prompt))
        } catch (ce: CancellationException) {
            // Propagate cooperative cancellation unchanged.
            throw ce
        } catch (t: Throwable) {
            val eligibleForFallback = primary.id == aicore.id && cloud.isAvailable()
            if (!eligibleForFallback) {
                _status.value = AiModelStatus.Error(t.message ?: "stream failed")
                throw t
            }
            useCloud = true
            _status.value = AiModelStatus.CloudFallback
            try {
                emitAll(cloud.stream(history, prompt))
            } catch (ce: CancellationException) {
                throw ce
            } catch (cloudErr: Throwable) {
                _status.value = AiModelStatus.Error(cloudErr.message ?: "cloud stream failed")
                throw cloudErr
            }
        }
    }
}
