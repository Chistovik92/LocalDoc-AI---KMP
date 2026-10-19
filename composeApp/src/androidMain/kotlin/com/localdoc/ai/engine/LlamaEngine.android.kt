package com.localdoc.ai.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Android-реализация: JNI-мост к llama.cpp (Vulkan на Adreno). */
actual class LlamaEngine actual constructor() {

    companion object {
        init {
            System.loadLibrary("localdoc_jni")
        }
    }

    private var sessionPtr: Long = 0

    interface TokenCallback {
        fun onToken(token: String)
    }

    private external fun initNative(modelPath: String, nCtx: Int, nGpuLayers: Int): Long
    private external fun generateNative(sessionPtr: Long, prompt: String, maxTokens: Int, callback: TokenCallback)
    private external fun stopNative(sessionPtr: Long)
    private external fun isGeneratingNative(sessionPtr: Long): Boolean
    private external fun freeNative(sessionPtr: Long)

    actual suspend fun load(modelPath: String, contextSize: Int, gpuLayers: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (sessionPtr != 0L) freeNative(sessionPtr)
            sessionPtr = initNative(modelPath, contextSize, gpuLayers)
            sessionPtr != 0L
        }

    actual fun generateStream(prompt: String, maxTokens: Int): Flow<String> = callbackFlow {
        val ptr = sessionPtr
        check(ptr != 0L) { "Модель не загружена" }

        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                trySend(token)
            }
        }

        generateNative(ptr, prompt, maxTokens, callback)
        close()

        awaitClose { /* генерация уже завершена синхронно */ }
    }.flowOn(Dispatchers.IO)

    actual fun stop() {
        if (sessionPtr != 0L) stopNative(sessionPtr)
    }

    actual fun unload() {
        if (sessionPtr != 0L) {
            stopNative(sessionPtr)
            freeNative(sessionPtr)
            sessionPtr = 0L
        }
    }

    actual fun isLoaded(): Boolean = sessionPtr != 0L
}
