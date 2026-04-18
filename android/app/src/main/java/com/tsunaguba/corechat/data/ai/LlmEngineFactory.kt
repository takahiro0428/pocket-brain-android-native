package com.tsunaguba.corechat.data.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions

/**
 * Seam so tests can swap the real MediaPipe runtime with a fake. The real
 * [LlmInference] class is a final with a static factory ([LlmInference.createFromOptions])
 * that can't be mocked directly without PowerMock/MockK extensions — hence this thin
 * indirection lets [MediaPipeLlmEngine] be unit-tested purely on the JVM without
 * loading the MediaPipe native libraries.
 */
interface LlmEngineFactory {
    /**
     * Load the Gemma model at [modelPath] and return a [LlmInference] ready for
     * streaming. Throws if the runtime cannot initialise — callers should wrap
     * this in the engine's error classification logic.
     */
    fun create(
        context: Context,
        modelPath: String,
        maxTokens: Int,
        topK: Int,
        temperature: Float,
    ): LlmInference
}

/**
 * Production factory that delegates straight to MediaPipe's static builder. Kept
 * trivial so the seam has no behaviour of its own that could drift from
 * [LlmInference.createFromOptions].
 */
class DefaultLlmEngineFactory : LlmEngineFactory {
    override fun create(
        context: Context,
        modelPath: String,
        maxTokens: Int,
        topK: Int,
        temperature: Float,
    ): LlmInference {
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTopK(topK)
            .setTemperature(temperature)
            .build()
        return LlmInference.createFromOptions(context, options)
    }
}
