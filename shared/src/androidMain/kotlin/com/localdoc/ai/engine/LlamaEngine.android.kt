package com.localdoc.ai.engine

import com.localdoc.ai.engine.NativeLlamaEngine.EngineException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Android-реализация: тонкая обёртка над автономным [NativeLlamaEngine] (engineAndroid). */
actual class LlamaEngine actual constructor() {

    private val delegate = NativeLlamaEngine()

    actual suspend fun load(modelPath: String, contextSize: Int, gpuLayers: Int): Boolean =
        delegate.load(modelPath, contextSize, gpuLayers)

    actual fun generateStream(prompt: String, maxTokens: Int): Flow<String> = flow {
        try {
            delegate.generateStream(prompt, maxTokens).collect { emit(it) }
        } catch (e: EngineException) {
            // Код localdoc_generate → локализованное сообщение пользователю (GenerationStatus)
            GenerationStatus.ensureOk(e.status)
        }
    }

    actual fun stop() = delegate.stop()

    actual fun unload() = delegate.unload()

    actual fun isLoaded(): Boolean = delegate.isLoaded()
}
