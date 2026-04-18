package com.tsunaguba.corechat.domain.model

/**
 * Which engine is backing the current Ready state. Lets the UI label the pill
 * with an engine-specific string so users can distinguish between the built-in
 * AICore path (Gemini Nano, Pixel/compatible devices), the MediaPipe path
 * (Gemma running locally, used when AICore is unavailable), and the cloud
 * fallback. Added in the 3-engine migration from the original 2-mode enum
 * (OnDevice + Cloud) — OnDevice was renamed to OnDeviceAiCore to avoid
 * ambiguity now that two on-device engines coexist.
 */
enum class AiEngineMode { OnDeviceAiCore, OnDeviceMediaPipe, Cloud }

/**
 * Categorises why the cloud engine is not usable so the UI can show a specific
 * remediation hint ("APIキーが設定されていません" vs "ネットワークに接続できません"),
 * instead of the old generic "ネットワークまたは API キーを確認…" catch-all that
 * left testers unable to self-diagnose.
 */
enum class UnavailableReason {
    /** BuildConfig.GEMINI_API_KEY was empty at build time (Secret missing). */
    ApiKeyMissing,
    /** Key was injected but suspiciously short — usually quote/whitespace contamination. */
    ApiKeyMalformed,
    /** Key present but the cloud probe returned 401/403 / PermissionDenied. */
    ApiKeyRejected,
    /** Model ID returned 404 NOT_FOUND — the model was retired by Google. */
    ModelNotFound,
    /** countTokens did not complete within CLOUD_PROBE_TIMEOUT_MS. */
    ProbeTimeout,
    /** UnknownHost / SSL / socket timeout — device can't reach the API. */
    NetworkUnreachable,
    // MediaPipe (Gemma on-device) failure modes — distinct from cloud reasons
    // so the UI can tell users "モデル取得に失敗" vs "APIキー無効", and so
    // AiEngineProvider can choose whether to retry the MediaPipe probe or
    // fall straight through to cloud on retry.
    /** Model download (HTTP GET) failed — network drop, 4xx/5xx, or stream error. */
    ModelDownloadFailed,
    /** Downloaded bytes didn't match the expected SHA-256 — corrupt/tampered file. */
    ModelChecksumMismatch,
    /** Device filesDir has insufficient free space for the model (~1.5GB + margin). */
    InsufficientStorage,
    /** LlmInference.createFromOptions threw — corrupt file, incompatible runtime, OOM. */
    ModelInitializationFailed,
    /** Unclassified failure; fall back to the generic message. */
    Unknown,
}

sealed interface AiModelStatus {
    data object Initializing : AiModelStatus
    data class Ready(val mode: AiEngineMode) : AiModelStatus
    data class Downloading(val progress: Float) : AiModelStatus
    data class Error(val reason: String) : AiModelStatus
    data class Unavailable(val reason: UnavailableReason = UnavailableReason.Unknown) : AiModelStatus
}

fun AiModelStatus.canSend(): Boolean = this is AiModelStatus.Ready
