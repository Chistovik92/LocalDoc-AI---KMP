package com.localdoc.ai.engine

import kotlinx.coroutines.flow.Flow

/**
 * Общий контракт движка локального инференса (llama.cpp).
 * Android: JNI + Vulkan; iOS: cinterop + Metal.
 */
expect class LlamaEngine() {
    /** Загружает GGUF-модель в память (mmap). Возвращает true при успехе. */
    suspend fun load(modelPath: String, contextSize: Int = 8192, gpuLayers: Int = 99): Boolean

    /** Потоковая генерация: на каждый валидный UTF-8 токен — элемент Flow. */
    fun generateStream(prompt: String, maxTokens: Int = 1536): Flow<String>

    /** Прерывает текущую генерацию. */
    fun stop()

    /** Выгружает модель из памяти. */
    fun unload()

    /** Признак того, что модель загружена. */
    fun isLoaded(): Boolean
}
