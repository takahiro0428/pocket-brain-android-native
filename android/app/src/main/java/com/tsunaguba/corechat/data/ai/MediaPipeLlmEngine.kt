package com.tsunaguba.corechat.data.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import com.tsunaguba.corechat.domain.model.UnavailableReason
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Priority-2 on-device engine backed by MediaPipe LiteRT-LM running Gemma 3n E2B.
 * Used when [AiCoreEngine] is unavailable (typically on Samsung devices that ship
 * the Google AICore APK without the Gemini Nano feature IDs the SDK requires).
 *
 * ### Lifecycle
 * 1. [isAvailable] first verifies the `.litertlm` file exists at [modelFile] and
 *    matches the expected byte size from BuildConfig.
 * 2. If the file is missing, [downloader] streams it from the configured URL.
 *    Progress is mirrored into [status] as [AiModelStatus.Downloading] so the
 *    existing UI (StatusBar + UnavailableCard) surfaces it without new code.
 * 3. Once the file is present and valid, [factory] creates an [LlmInference]
 *    under a 30 s timeout — loading a 1.5 GB model from cold flash is slow.
 * 4. Failure modes are mapped to [UnavailableReason] values so the UI can show
 *    a specific hint instead of the generic "AI利用不可".
 *
 * ### Why mirror the AiCoreEngine shape
 * `AiEngineProvider` already `combine`s engine statuses; re-using the same
 * `status: StateFlow<AiModelStatus>` contract means the provider doesn't need
 * a separate path for MediaPipe download progress.
 */
class MediaPipeLlmEngine(
    private val context: Context,
    private val downloader: GemmaModelDownloader,
    private val factory: LlmEngineFactory,
    private val modelFile: File = File(context.filesDir, DEFAULT_MODEL_SUBPATH),
    private val expectedSizeBytes: Long,
    private val modelUrl: String,
) : AiEngine {

    override val id: String = ENGINE_ID

    private val _status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
    val status: StateFlow<AiModelStatus> = _status.asStateFlow()

    /**
     * Reason classification from the most recent [isAvailable] call. `null` means
     * either the probe succeeded or was never attempted. Read by [AiEngineProvider]
     * right after a false return to surface the cause in the UI.
     */
    @Volatile
    var lastUnavailableReason: UnavailableReason? = null
        private set

    // `llm` writes happen in two places: init under modelMutex (atomic with
    // the factory.create call), and awaitClose which must run from a MediaPipe
    // worker thread where we can't suspend to acquire the mutex. AtomicReference
    // lets awaitClose do a lock-free compareAndSet(engine, null) so only the
    // specific engine instance that just closed is cleared, without risk of
    // wiping a newer llm that a concurrent isAvailable() just installed.
    private val modelMutex = Mutex()
    private val llmRef = AtomicReference<LlmInference?>(null)

    /**
     * Prevents two concurrent stream() calls from tearing down each other's
     * engine. MediaPipe 0.10.27 has no per-request cancel so we serialise whole
     * stream sessions; the second caller waits for the first to complete or be
     * cancelled before starting. Acquired inside stream(), held across the
     * callbackFlow's awaitClose so close-and-reinit is atomic.
     */
    private val streamMutex = Mutex()

    /**
     * Availability probe. A `true` return value means the engine *was* ready at
     * the moment of the call; it does NOT guarantee that a subsequent [stream]
     * can use the engine without re-initialising, because a concurrent
     * `stream()` cancellation may close the underlying [LlmInference] between
     * the probe and the next stream call. [stream] handles this by lazily
     * rebuilding the engine inside [modelMutex]. Callers should treat
     * isAvailable() as a best-effort routing hint, not a capability lock.
     */
    override suspend fun isAvailable(): Boolean {
        // An empty URL means the operator hasn't configured GEMMA_MODEL_URL (Secret
        // not set in CI). We treat this as "MediaPipe disabled" rather than a real
        // failure so the provider can route straight to cloud without flashing a
        // download error to the user.
        if (modelUrl.isBlank() || expectedSizeBytes <= 0L) {
            setUnavailable(UnavailableReason.ModelInitializationFailed)
            return false
        }

        // Already initialised in this process — AtomicReference.get is lock-free
        // and synchronises with awaitClose's compareAndSet, so we can skip the
        // mutex on this happy path.
        if (llmRef.get() != null) {
            lastUnavailableReason = null
            _status.value = AiModelStatus.Ready(AiEngineMode.OnDeviceMediaPipe)
            return true
        }

        // Phase 1: ensure the model file is present and uncorrupted. Run outside
        // the mutex so the 1.5GB download doesn't block other isAvailable() calls
        // (the download itself is serialised inside GemmaModelDownloader).
        val ensureResult = runCatching {
            downloader.ensure(
                target = modelFile,
                expectedSizeBytes = expectedSizeBytes,
                url = modelUrl,
                onProgress = { progress ->
                    // Only surface progress while we're actively downloading. We guard
                    // against re-emitting Downloading(1.0f) once initialisation starts,
                    // because that state would suggest to the UI that the SDK is still
                    // downloading when it's actually loading into memory.
                    _status.value = AiModelStatus.Downloading(progress.coerceIn(0f, 1f))
                },
            )
        }
        val ensureError = ensureResult.exceptionOrNull()
        if (ensureError != null) {
            if (ensureError is CancellationException) throw ensureError
            val reason = when (ensureError) {
                is GemmaModelDownloader.InsufficientStorageException -> UnavailableReason.InsufficientStorage
                is GemmaModelDownloader.ChecksumMismatchException -> UnavailableReason.ModelChecksumMismatch
                else -> UnavailableReason.ModelDownloadFailed
            }
            android.util.Log.w(TAG, "Gemma model ensure failed: $reason", ensureError)
            setUnavailable(reason)
            return false
        }

        // Phase 2: load the model into MediaPipe. Bounded so a wedged runtime
        // doesn't leave status stuck in Initializing. Mutex prevents two
        // concurrent isAvailable() calls from both creating an engine and
        // leaking one.
        return modelMutex.withLock {
            val cached = llmRef.get()
            if (cached != null) {
                lastUnavailableReason = null
                _status.value = AiModelStatus.Ready(AiEngineMode.OnDeviceMediaPipe)
                return@withLock true
            }
            val initResult = runCatching {
                withTimeout(INIT_TIMEOUT_MS) {
                    factory.create(
                        context = context,
                        modelPath = modelFile.absolutePath,
                        maxTokens = DEFAULT_MAX_TOKENS,
                        topK = DEFAULT_TOP_K,
                        temperature = DEFAULT_TEMPERATURE,
                    )
                }
            }
            val err = initResult.exceptionOrNull()
            if (err != null) {
                if (err is CancellationException && err !is TimeoutCancellationException) throw err
                android.util.Log.w(TAG, "MediaPipe init failed", err)
                setUnavailable(UnavailableReason.ModelInitializationFailed)
                false
            } else {
                llmRef.set(initResult.getOrNull())
                lastUnavailableReason = null
                _status.value = AiModelStatus.Ready(AiEngineMode.OnDeviceMediaPipe)
                true
            }
        }
    }

    override fun stream(history: List<ChatMessage>, prompt: String): Flow<String> = callbackFlow {
        // Serialise concurrent stream() calls — MediaPipe's LlmInference has no
        // per-request cancellation, so running two overlapping streams would
        // require two engines or fight over one; we pick "wait your turn".
        streamMutex.lock()
        // Idempotent release: awaitClose's cleanup and the finally-block both
        // call this, so a cancellation thrown *before* awaitClose is reached
        // (e.g. during the suspending modelMutex.withLock call) still releases
        // the mutex. Without this guard, callbackFlow's producer unwinds before
        // awaitClose runs and the next stream() would deadlock on streamMutex.
        var unlocked = false
        fun releaseStreamLockOnce() {
            if (!unlocked) {
                unlocked = true
                streamMutex.unlock()
            }
        }
        try {
            val engine = modelMutex.withLock {
                // Lazy re-init: if an earlier stream() closed the engine in awaitClose,
                // rebuild it here on demand so AiEngineProvider.stream() can safely
                // invoke us back-to-back without the caller having to re-probe. This
                // also rescues the common "user taps send, cancel, send again" pattern.
                val cached = llmRef.get()
                if (cached != null) return@withLock cached
                val opened = runCatching {
                    factory.create(
                        context = context,
                        modelPath = modelFile.absolutePath,
                        maxTokens = DEFAULT_MAX_TOKENS,
                        topK = DEFAULT_TOP_K,
                        temperature = DEFAULT_TEMPERATURE,
                    )
                }.getOrNull() ?: error(
                    "MediaPipeLlmEngine.stream called before isAvailable() returned true",
                )
                llmRef.set(opened)
                opened
            }
            val composed = composePrompt(history, prompt)

            // generateResponseAsync invokes the listener with (partialResult, done) tuples
            // on a MediaPipe-internal thread. We mirror each partial into the flow channel
            // and close on `done`. Exceptions from native code surface via trySendBlocking
            // returning failure; we convert those to a flow error so the provider can
            // decide whether to fall back to cloud.
            try {
                engine.generateResponseAsync(composed) { partialResult: String, done: Boolean ->
                    val sent = trySend(partialResult)
                    if (sent.isFailure) {
                        close(sent.exceptionOrNull() ?: IllegalStateException("channel send failed"))
                        return@generateResponseAsync
                    }
                    if (done) close()
                }
            } catch (t: Throwable) {
                // Starting the async call itself failed (e.g. engine already closed).
                // Close the channel with the error and let awaitClose clean up.
                close(t)
            }

            awaitClose {
                // MediaPipe's LlmInference doesn't expose a cancel(requestId) API in
                // 0.10.27 — close() tears down the whole session. compareAndSet only
                // clears llmRef if it still points to *our* engine, so a concurrent
                // isAvailable() that just installed a replacement is preserved.
                // runBlocking is deliberately avoided here because awaitClose runs on
                // an arbitrary MediaPipe-internal thread where suspending is unsafe.
                runCatching { engine.close() }
                llmRef.compareAndSet(engine, null)
                releaseStreamLockOnce()
            }
        } finally {
            // Belt and braces: if a cancellation unwound the producer before
            // awaitClose ran (e.g. during modelMutex.withLock), the awaitClose
            // cleanup never fires and streamMutex would stay locked forever.
            // This finally block makes that impossible — the idempotent guard
            // above prevents a double-unlock when awaitClose *did* run.
            releaseStreamLockOnce()
        }
    }

    private fun setUnavailable(reason: UnavailableReason) {
        lastUnavailableReason = reason
        _status.value = AiModelStatus.Unavailable(reason)
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
        const val ENGINE_ID = "mediapipe-gemma"
        private const val TAG = "MediaPipeLlmEngine"

        /**
         * Internal-storage subpath for the downloaded model. Using filesDir (not
         * cacheDir) so the OS doesn't evict the 1.5 GB file under memory pressure.
         */
        const val DEFAULT_MODEL_SUBPATH = "models/gemma.litertlm"

        /** Bound on createFromOptions — cold-loading 1.5 GB from flash can take ~15 s. */
        const val INIT_TIMEOUT_MS: Long = 30_000L

        private const val DEFAULT_MAX_TOKENS = 1024
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TEMPERATURE = 0.8f
        private const val MAX_HISTORY_TURNS = 8
    }
}
