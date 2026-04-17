package com.tsunaguba.corechat.domain.model

sealed interface AiModelStatus {
    data object Initializing : AiModelStatus
    data object Ready : AiModelStatus
    data class Downloading(val progress: Float) : AiModelStatus
    data object CloudFallback : AiModelStatus
    data class Error(val reason: String) : AiModelStatus
    data object Unavailable : AiModelStatus
}

fun AiModelStatus.canSend(): Boolean = when (this) {
    AiModelStatus.Ready, AiModelStatus.CloudFallback -> true
    else -> false
}
