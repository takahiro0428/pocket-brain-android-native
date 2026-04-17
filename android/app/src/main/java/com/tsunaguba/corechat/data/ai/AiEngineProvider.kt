package com.tsunaguba.corechat.data.ai

import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Routes requests to the best available engine:
 *   1. AICore (on-device) if the device reports it available
 *   2. Cloud Gemini otherwise
 *
 * If an AICore stream fails mid-flight, a single cloud-fallback retry is attempted
 * for that request without losing the user's prompt.
 *
 * ### Status SoT
 * Status is derived from two sources via [combine]: the underlying AICore engine's
 * status and an internal `useCloudFlow`. When `useCloudFlow == true` we ignore the
 * AICore signal and expose `Ready(Cloud)` (or `Unavailable` if cloud itself is
 * unusable). This keeps the status state machine single-sourced.
 */
class AiEngineProvider(
    private val aicore: AiCoreEngine,
    private val cloud: CloudGeminiEngine,
    externalScope: CoroutineScope,
) {

    private val useCloudFlow = MutableStateFlow(false)
    private val cloudUsable = MutableStateFlow<Boolean?>(null) // null = not probed yet
    // Persistent error override — set when a stream call permanently fails
    // (no fallback possible). Cleared at the start of every `stream()` attempt and
    // at the start of every `retry()`, so a successful recovery automatically
    // removes the "エラーが発生しました" pill.
    private val persistentError = MutableStateFlow<AiModelStatus.Error?>(null)

    val status: StateFlow<AiModelStatus> = combine(
        aicore.status,
        useCloudFlow,
        cloudUsable,
        persistentError,
    ) { aicoreStatus, useCloud, cloudOk, error ->
        when {
            error != null -> error
            !useCloud -> aicoreStatus
            cloudOk == true -> AiModelStatus.Ready(AiEngineMode.Cloud)
            cloudOk == false -> AiModelStatus.Unavailable
            else -> AiModelStatus.Initializing
        }
    }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = AiModelStatus.Initializing,
    )

    init {
        externalScope.launch {
            val aicoreOk = aicore.isAvailable()
            if (!aicoreOk) {
                val cloudOk = cloud.isAvailable()
                cloudUsable.value = cloudOk
                useCloudFlow.value = true
            }
        }
    }

    /**
     * Explicit retry entry point (e.g. from a "再試行" UI button). Re-probes AICore;
     * if it succeeds, we revert to on-device routing for the next request.
     */
    suspend fun retry() {
        persistentError.value = null
        val aicoreOk = aicore.isAvailable()
        if (aicoreOk) {
            useCloudFlow.value = false
        } else {
            val cloudOk = cloud.isAvailable()
            cloudUsable.value = cloudOk
            useCloudFlow.value = true
        }
    }

    fun stream(history: List<ChatMessage>, prompt: String): Flow<String> = flow {
        // Clear any stale persistent error from a previous attempt. If this stream
        // also fails, the catch block below re-sets it; if it succeeds, the user
        // no longer sees a stuck "エラーが発生しました" pill after recovery.
        persistentError.value = null
        val primary = if (useCloudFlow.value) cloud else aicore
        try {
            emitAll(primary.stream(history, prompt))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Referential equality is safer than string comparison since both engines are
            // singletons held by the provider.
            val eligibleForFallback = primary === aicore && cloud.isAvailable()
            if (!eligibleForFallback) {
                android.util.Log.w(TAG, "stream failed with no eligible fallback", t)
                // Surface as persistent error so the UI shows "エラーが発生しました"
                // and the send button stays disabled until retry() succeeds.
                persistentError.value = AiModelStatus.Error("stream-failed")
                throw t
            }
            android.util.Log.w(TAG, "primary stream failed; switching to cloud fallback", t)
            // `cloud.isAvailable()` returned true above, so reflect that authoritative
            // probe into the state flow. Atomic enough: both writes target distinct flows
            // and the combine handles any transient interleaving safely.
            cloudUsable.value = true
            useCloudFlow.value = true
            try {
                emitAll(cloud.stream(history, prompt))
            } catch (ce: CancellationException) {
                throw ce
            } catch (cloudErr: Throwable) {
                android.util.Log.w(TAG, "cloud fallback also failed", cloudErr)
                // Probe said "usable" but the real stream failed — force a re-probe on
                // the next retry() so we don't lie about readiness again.
                cloudUsable.value = null
                persistentError.value = AiModelStatus.Error("cloud-fallback-failed")
                throw cloudErr
            }
        }
    }

    private companion object {
        private const val TAG = "AiEngineProvider"
    }
}
