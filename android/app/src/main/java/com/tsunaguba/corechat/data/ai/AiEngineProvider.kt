package com.tsunaguba.corechat.data.ai

import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.UnavailableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Routes requests to the best available engine in priority order:
 *   1. AICore (on-device Gemini Nano) — fastest, most private
 *   2. MediaPipe Gemma (on-device LiteRT-LM) — fallback for devices without
 *      Gemini Nano feature IDs (e.g. many Samsung/OEM phones)
 *   3. Cloud Gemini — last-resort network call
 *
 * If an on-device stream fails mid-flight, we attempt to fall forward through
 * the chain (aicore → mediapipe → cloud) for that single request so the user's
 * prompt isn't lost to a transient engine failure.
 *
 * ### Status SoT
 * The combined status flow derives from 4 inputs:
 *  - `aicore.status` — AICore-specific state (Downloading/Ready/Error)
 *  - `mediapipe.status` — MediaPipe-specific state (covers Gemma download progress)
 *  - `probeState` — which route won the priority race, plus the rejection reasons
 *    from every route we tried (so retry can re-use that info for the UI)
 *  - `persistentError` — single-request terminal error that outlives the stream
 *
 * Why [ProbeState] is a single data class: we tried a naive 6-input combine but
 * Kotlin's `combine(vararg)` overload erases types to `Array<Any?>` and we'd
 * lose compile-time safety. Packing the route + reasons into one value keeps
 * the status-derivation block type-safe.
 */
class AiEngineProvider(
    private val aicore: AiCoreEngine,
    private val mediapipe: MediaPipeLlmEngine,
    private val cloud: CloudGeminiEngine,
    externalScope: CoroutineScope,
) {

    enum class EngineRoute { AiCore, MediaPipe, Cloud }

    /**
     * Snapshot of the provider's routing decision. `probed*` booleans disambiguate
     * "not tried yet" from "tried and succeeded" so the combined status can return
     * Initializing before any probe has completed.
     */
    private data class ProbeState(
        val route: EngineRoute,
        val mpReason: UnavailableReason?,
        val cloudReason: UnavailableReason?,
        val mpProbed: Boolean,
        val cloudProbed: Boolean,
    )

    private val probeState = MutableStateFlow(
        ProbeState(
            route = EngineRoute.AiCore,
            mpReason = null,
            cloudReason = null,
            mpProbed = false,
            cloudProbed = false,
        ),
    )

    // Persistent error override — set when a stream call permanently fails
    // (no fallback possible). Cleared at the start of every `stream()` attempt and
    // at the start of every `retry()`, so a successful recovery automatically
    // removes the "エラーが発生しました" pill.
    private val persistentError = MutableStateFlow<AiModelStatus.Error?>(null)

    val status: StateFlow<AiModelStatus> = combine(
        aicore.status,
        mediapipe.status,
        probeState,
        persistentError,
    ) { aicoreStatus, mpStatus, probe, error ->
        when {
            error != null -> error
            probe.route == EngineRoute.AiCore -> aicoreStatus
            probe.route == EngineRoute.MediaPipe -> {
                // MediaPipe's own status already encodes Downloading(progress) and
                // Ready(OnDeviceMediaPipe) / Unavailable(reason). Relay it directly
                // so the provider doesn't duplicate its state machine.
                mpStatus
            }
            probe.route == EngineRoute.Cloud -> {
                if (!probe.cloudProbed) AiModelStatus.Initializing
                else if (probe.cloudReason == null) AiModelStatus.Ready(AiEngineMode.Cloud)
                else AiModelStatus.Unavailable(probe.cloudReason)
            }
            else -> AiModelStatus.Initializing
        }
    }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = AiModelStatus.Initializing,
    )

    init {
        externalScope.launch {
            probeChain()
        }
    }

    /**
     * Explicit retry entry point (e.g. from a "再試行" UI button). Re-probes the
     * full chain from AICore down. If any earlier-priority engine recovers we
     * switch back to it for the next request.
     */
    suspend fun retry() {
        persistentError.value = null
        probeChain()
    }

    /**
     * Probe AICore → MediaPipe → Cloud in order, update [probeState] to reflect
     * which route won. Failure reasons are carried along so the UI's
     * UnavailableCard can show a specific hint when the final route is Cloud+failed.
     */
    private suspend fun probeChain() {
        if (aicore.isAvailable()) {
            probeState.value = ProbeState(
                route = EngineRoute.AiCore,
                mpReason = null,
                cloudReason = null,
                mpProbed = false,
                cloudProbed = false,
            )
            return
        }
        if (mediapipe.isAvailable()) {
            probeState.value = ProbeState(
                route = EngineRoute.MediaPipe,
                mpReason = null,
                cloudReason = null,
                mpProbed = true,
                cloudProbed = false,
            )
            return
        }
        // Both on-device engines are unavailable — try cloud and lock in whatever
        // result we get. We keep mpReason so a later UI surface can show why the
        // local engine was skipped.
        val mpReason = mediapipe.lastUnavailableReason ?: UnavailableReason.Unknown
        val cloudOk = cloud.isAvailable()
        probeState.value = ProbeState(
            route = EngineRoute.Cloud,
            mpReason = mpReason,
            cloudReason = if (cloudOk) null else (cloud.lastUnavailableReason ?: UnavailableReason.Unknown),
            mpProbed = true,
            cloudProbed = true,
        )
    }

    fun stream(history: List<ChatMessage>, prompt: String): Flow<String> = flow {
        // Clear any stale persistent error from a previous attempt. If this stream
        // also fails, the catch block below re-sets it; if it succeeds, the user
        // no longer sees a stuck "エラーが発生しました" pill after recovery.
        persistentError.value = null

        val route = probeState.value.route
        val primary = engineForRoute(route)

        try {
            emitAll(primary.stream(history, prompt))
            return@flow
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "primary ($route) stream failed; attempting fallback", t)
            // Fall forward through the rest of the chain. Each `attemptFallback`
            // tries the next engine in order and returns true on success.
            if (route == EngineRoute.AiCore) {
                if (tryStreamWithFallback(history, prompt, from = EngineRoute.AiCore)) return@flow
            } else if (route == EngineRoute.MediaPipe) {
                if (tryStreamWithFallback(history, prompt, from = EngineRoute.MediaPipe)) return@flow
            }
            // No further fallback succeeded.
            persistentError.value = AiModelStatus.Error(reason = "stream-failed")
            throw t
        }
    }

    /**
     * Walk the priority chain starting *after* [from], trying each engine's
     * `isAvailable()` then `stream()`. Emits into the enclosing [flow] on
     * success. Returns true if some downstream engine succeeded.
     */
    private suspend fun FlowCollector<String>.tryStreamWithFallback(
        history: List<ChatMessage>,
        prompt: String,
        from: EngineRoute,
    ): Boolean {
        val chain = when (from) {
            EngineRoute.AiCore -> listOf(EngineRoute.MediaPipe, EngineRoute.Cloud)
            EngineRoute.MediaPipe -> listOf(EngineRoute.Cloud)
            EngineRoute.Cloud -> emptyList()
        }
        for (next in chain) {
            val engine = engineForRoute(next)
            val available = try {
                engine.isAvailable()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
            if (!available) continue
            // Commit the route change so the status pill updates before we start
            // the fallback stream.
            probeState.value = probeState.value.copy(
                route = next,
                mpProbed = if (next != EngineRoute.AiCore) true else probeState.value.mpProbed,
                cloudProbed = if (next == EngineRoute.Cloud) true else probeState.value.cloudProbed,
                mpReason = if (next == EngineRoute.MediaPipe) null else probeState.value.mpReason,
                cloudReason = if (next == EngineRoute.Cloud) null else probeState.value.cloudReason,
            )
            try {
                emitAll(engine.stream(history, prompt))
                return true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "fallback ($next) stream failed", t)
                // continue to the next in the chain
            }
        }
        return false
    }

    private fun engineForRoute(route: EngineRoute): AiEngine = when (route) {
        EngineRoute.AiCore -> aicore
        EngineRoute.MediaPipe -> mediapipe
        EngineRoute.Cloud -> cloud
    }

    private companion object {
        private const val TAG = "AiEngineProvider"
    }
}
