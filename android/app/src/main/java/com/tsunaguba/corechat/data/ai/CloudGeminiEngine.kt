package com.tsunaguba.corechat.data.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.generationConfig
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cloud Gemini engine used as fallback when AICore is unavailable.
 * API key is injected via BuildConfig.GEMINI_API_KEY (sourced from local.properties or CI secret).
 */
class CloudGeminiEngine(
    private val apiKey: String,
    modelName: String = DEFAULT_MODEL,
) : AiEngine {

    override val id: String = "cloud-gemini"

    private val model: GenerativeModel? = if (apiKey.isNotBlank()) {
        GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 512
            },
        )
    } else {
        null
    }

    override suspend fun isAvailable(): Boolean = model != null

    override fun stream(history: List<ChatMessage>, prompt: String): Flow<String> {
        val m = model ?: error("CloudGeminiEngine has no API key configured")
        val chat = m.startChat(history = history.toContentList())
        return chat.sendMessageStream(prompt).map { it.text.orEmpty() }
    }

    private fun List<ChatMessage>.toContentList(): List<Content> = map { msg ->
        Content(
            role = if (msg.role == Role.USER) "user" else "model",
            parts = listOf(TextPart(msg.content)),
        )
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-1.5-flash"
    }
}
