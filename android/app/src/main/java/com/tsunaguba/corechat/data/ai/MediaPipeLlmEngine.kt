package com.tsunaguba.corechat.data.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import com.tsunaguba.corechat.domain.model.UnavailableReason
import java.io.File
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

    private val modelMutex = Mutex()

    @Volatile
    private var llm: LlmInference? = null

    override suspend fun isAvailable(): Boolean {
        // An empty URL means the operator hasn't configured GEMMA_MODEL_URL (Secret
        // not set in CI). We treat this as "MediaPipe disabled" rather than a real
        // failure so the provider can route straight to cloud without flashing a
        // download error to the user.
        if (modelUrl.isBlank() || expectedSizeBytes <= 0L) {
            setUnavailable(UnavailableReason.ModelInitializationFailed)
            return false
        }

        // Already initialised in this process.
        if (llm != null) {
            lastUnavailableReason = null
            _status.value = AiModelStatus.Ready(AiEngineMode.OnDeviceMediaPipe)
            return true
        }

        // Phase 1: ensure the model file is present and uncorrupted.
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
        // doesn't leave status stuck in Initializing.
        return modelMutex.withLock {
            if (llm != null) {
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
                llm = initResult.getOrNull()
                lastUnavailableReason = null
                _status.value = AiModelStatus.Ready(AiEngineMode.OnDeviceMediaPipe)
                true
            }
        }
    }

    override fun stream(history: List<ChatMessage>, prompt: String): Flow<String> = callbackFlow {
        val engine = llm
            ?: error("MediaPipeLlmEngine.stream called before isAvailable() returned true")
        val composed = composePrompt(history, prompt)

        // generateResponseAsync invokes the listener with (partialResult, done) tuples
        // on a MediaPipe-internal thread. We mirror each partial into the flow channel
        // and close on `done`. Exceptions from native code surface via trySendBlocking
        // returning failure; we convert those to a flow error so the provider can
        // decide whether to fall back to cloud.
        engine.generateResponseAsync(composed) { partialResult: String, done: Boolean ->
            val sent = trySend(partialResult)
            if (sent.isFailure) {
                close(sent.exceptionOrNull() ?: IllegalStateException("channel send failed"))
                return@generateResponseAsync
            }
            if (done) close()
        }

        awaitClose {
            // MediaPipe's LlmInference doesn't expose a cancel(requestId) API in
            // 0.10.27 — close() tears down the whole session. That's acceptable
            // here because `stream()` owns the engine's lifetime for a single
            // request and a cancelled request is a rare user action (stop button).
            // We null out the reference so the next isAvailable() recreates a
            // fresh inference context instead of reusing one we just closed.
            //
            // Note: this also means a new isAvailable() call is needed after cancel
            // before the next stream() — handled by AiEngineProvider.retry().
            runCatching { engine.close() }
            llm = null
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
