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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
     * Snapshot of the provider's routing decision. `probeComplete` flips true
     * once probeChain() finishes for the first time — until then stream() must
     * wait instead of routing to a stale default. `mpProbed` / `cloudProbed`
     * capture per-engine probe success for the status-derivation logic.
     */
    private data class ProbeState(
        val route: EngineRoute,
        val mpReason: UnavailableReason?,
        val cloudReason: UnavailableReason?,
        val mpProbed: Boolean,
        val cloudProbed: Boolean,
        val probeComplete: Boolean,
    )

    /**
     * Initial state: no probe has run yet. `route` defaults to AiCore purely so
     * the data class is non-null; it's never read while probeComplete=false
     * because stream() waits for completion first (see awaitProbeComplete()).
     */
    private val probeState = MutableStateFlow(
        ProbeState(
            route = EngineRoute.AiCore,
            mpReason = null,
            cloudReason = null,
            mpProbed = false,
            cloudProbed = false,
            probeComplete = false,
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
            // Hold Initializing until probeChain() finalises the route, so the UI
            // never flashes a stale "Ready (AICore)" in the millisecond before the
            // probe on a Samsung device flips us over to MediaPipe or Cloud.
            !probe.probeComplete -> AiModelStatus.Initializing
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
     *
     * Writes use [MutableStateFlow.update] so concurrent `probeChain` + `retry()`
     * + `tryStreamWithFallback` cannot lose updates; each call sees the latest
     * snapshot before producing the next one.
     *
     * Defensive guard: if any engine's isAvailable() throws a non-cancellation
     * exception (should not happen per AiEngine contract but still) we always
     * mark probeComplete=true on exit so stream() doesn't suspend indefinitely
     * inside awaitProbeComplete().
     */
    private suspend fun probeChain() {
        var completed = false
        try {
            probeChainImpl()
            completed = true
        } finally {
            if (!completed) {
                probeState.update { it.copy(probeComplete = true) }
            }
        }
    }

    private suspend fun probeChainImpl() {
        if (aicore.isAvailable()) {
            probeState.update {
                ProbeState(
                    route = EngineRoute.AiCore,
                    mpReason = null,
                    cloudReason = null,
                    mpProbed = false,
                    cloudProbed = false,
                    probeComplete = true,
                )
            }
            return
        }
        if (mediapipe.isAvailable()) {
            probeState.update {
                ProbeState(
                    route = EngineRoute.MediaPipe,
                    mpReason = null,
                    cloudReason = null,
                    mpProbed = true,
                    cloudProbed = false,
                    probeComplete = true,
                )
            }
            return
        }
        // Both on-device engines are unavailable — try cloud and lock in whatever
        // result we get. We keep mpReason so a later UI surface can show why the
        // local engine was skipped.
        val mpReason = mediapipe.lastUnavailableReason ?: UnavailableReason.Unknown
        val cloudOk = cloud.isAvailable()
        probeState.update {
            ProbeState(
                route = EngineRoute.Cloud,
                mpReason = mpReason,
                cloudReason = if (cloudOk) null else (cloud.lastUnavailableReason ?: UnavailableReason.Unknown),
                mpProbed = true,
                cloudProbed = true,
                probeComplete = true,
            )
        }
    }

    /**
     * Suspends until probeChain() has produced at least one finalised result.
     * Called from [stream] so the very first send after app launch doesn't
     * silently route to a stale initial `EngineRoute.AiCore` while the probe
     * is still in flight.
     */
    private suspend fun awaitProbeComplete() {
        if (probeState.value.probeComplete) return
        probeState.filter { it.probeComplete }.first()
    }

    fun stream(history: List<ChatMessage>, prompt: String): Flow<String> = flow {
        // Clear any stale persistent error from a previous attempt. If this stream
        // also fails, the catch block below re-sets it; if it succeeds, the user
        // no longer sees a stuck "エラーが発生しました" pill after recovery.
        persistentError.value = null

        // Block until probeChain has committed a route — otherwise the very first
        // user message after app launch would race the probe and route to the
        // stale initial EngineRoute.AiCore default.
        awaitProbeComplete()

        val route = probeState.value.route
        val primary = engineForRoute(route)

        // Re-probe the chosen engine right before stream() so transient conditions
        // (MediaPipe engine closed by a previous stream's awaitClose, AICore
        // download in progress, etc.) are detected without surfacing as a mid-
        // stream crash. If the engine is no longer available, walk the chain
        // before emitting a single token.
        val primaryReady = try {
            primary.isAvailable()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
        if (!primaryReady) {
            if (!tryStreamWithFallback(history, prompt, from = route, emittedAny = false)) {
                persistentError.value = AiModelStatus.Error(reason = "stream-failed")
                error("no engine available in chain from $route")
            }
            return@flow
        }

        var emittedAny = false
        try {
            primary.stream(history, prompt).collect { chunk ->
                emittedAny = true
                emit(chunk)
            }
            return@flow
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "primary ($route) stream failed; attempting fallback", t)
            // Partial-emit guard: once any token has been delivered to the UI, a
            // fallback would concatenate a second engine's full response onto the
            // first engine's truncated prefix — a confusing garbage output. Treat
            // post-emit failures as terminal and let the caller retry explicitly.
            if (emittedAny) {
                persistentError.value = AiModelStatus.Error(reason = "stream-partial-failed")
                throw t
            }
            // Fall forward through the rest of the chain. Each `attemptFallback`
            // tries the next engine in order and returns true on success.
            if (tryStreamWithFallback(history, prompt, from = route, emittedAny = false)) return@flow
            // No further fallback succeeded.
            persistentError.value = AiModelStatus.Error(reason = "stream-failed")
            throw t
        }
    }

    /**
     * Walk the priority chain starting *after* [from], trying each engine's
     * `isAvailable()` then `stream()`. Emits into the enclosing [flow] on
     * success. Returns true if some downstream engine succeeded.
     *
     * @param emittedAny set by the caller when the primary has already emitted
     *        at least one token — prevents further fallback to avoid concatenating
     *        garbage into the UI.
     */
    private suspend fun FlowCollector<String>.tryStreamWithFallback(
        history: List<ChatMessage>,
        prompt: String,
        from: EngineRoute,
        emittedAny: Boolean,
    ): Boolean {
        if (emittedAny) return false
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
            // the fallback stream. Using update{} to serialise with concurrent
            // probeChain() / retry() writes.
            probeState.update { current ->
                current.copy(
                    route = next,
                    mpProbed = if (next != EngineRoute.AiCore) true else current.mpProbed,
                    cloudProbed = if (next == EngineRoute.Cloud) true else current.cloudProbed,
                    mpReason = if (next == EngineRoute.MediaPipe) null else current.mpReason,
                    cloudReason = if (next == EngineRoute.Cloud) null else current.cloudReason,
                )
            }
            var localEmitted = false
            try {
                engine.stream(history, prompt).collect { chunk ->
                    localEmitted = true
                    emit(chunk)
                }
                return true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "fallback ($next) stream failed", t)
                // Same partial-emit guard as the primary path: once we have sent a
                // chunk to the collector, a further fallback would mix two engines'
                // outputs. Stop and let the caller surface the error.
                if (localEmitted) throw t
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
