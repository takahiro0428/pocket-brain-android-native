package com.tsunaguba.corechat.domain.model

/**
 * Which engine is backing the current Ready state. Lets the UI label the pill
 * as "オンデバイスAI 準備完了" vs "クラウドAI 準備完了" without the old "クラウド接続中"
 * wording that users mistook for an indefinite loading state.
 */
enum class AiEngineMode { OnDevice, Cloud }

sealed interface AiModelStatus {
    data object Initializing : AiModelStatus
    data class Ready(val mode: AiEngineMode) : AiModelStatus
    data class Downloading(val progress: Float) : AiModelStatus
    data class Error(val reason: String) : AiModelStatus
    data object Unavailable : AiModelStatus
}

fun AiModelStatus.canSend(): Boolean = this is AiModelStatus.Ready
