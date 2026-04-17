package com.tsunaguba.corechat.domain.model

import java.util.UUID

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
)
