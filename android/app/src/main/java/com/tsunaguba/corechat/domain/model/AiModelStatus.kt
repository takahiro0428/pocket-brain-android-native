package com.tsunaguba.corechat.domain.model

/**
 * Which engine is backing the current Ready state. Lets the UI label the pill
 * as "オンデバイスAI 準備完了" vs "クラウドAI 準備完了" without the old "クラウド接続中"
 * wording that users mistook for an indefinite loading state.
 */
enum class AiEngineMode { OnDevice, Cloud }

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
    /** countTokens did not complete within CLOUD_PROBE_TIMEOUT_MS. */
    ProbeTimeout,
    /** UnknownHost / SSL / socket timeout — device can't reach the API. */
    NetworkUnreachable,
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
