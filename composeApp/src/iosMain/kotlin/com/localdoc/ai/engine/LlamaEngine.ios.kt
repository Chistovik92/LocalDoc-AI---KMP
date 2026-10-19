package com.localdoc.ai.engine

import com.localdoc.ai.cinterop.LocalDocSession
import com.localdoc.ai.cinterop.localdoc_free
import com.localdoc.ai.cinterop.localdoc_generate
import com.localdoc.ai.cinterop.localdoc_init
import com.localdoc.ai.cinterop.localdoc_stop
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** iOS-реализация: прямой вызов C-ядра llama.cpp через cinterop (Metal, без JNI). */
@OptIn(ExperimentalForeignApi::class)
actual class LlamaEngine actual constructor() {

    private var sessionPtr: CValuesRef<LocalDocSession>? = null

    actual suspend fun load(modelPath: String, contextSize: Int, gpuLayers: Int): Boolean =
        withContext(Dispatchers.IO) {
            unload()
            sessionPtr = localdoc_init(modelPath, contextSize, gpuLayers)
            sessionPtr != null
        }

    actual fun generateStream(prompt: String, maxTokens: Int): Flow<String> = callbackFlow {
        val currentSession = sessionPtr ?: error("Модель не загружена")

        // Оборачиваем канал в StableRef, чтобы безопасно передать его в C-коллбэк
        val channelRef = StableRef.create(this.channel)

        val cCallback = staticCFunction { tokenPtr: CPointer<ByteVar>?, userData: COpaquePointer? ->
            if (tokenPtr != null && userData != null) {
                val ch = userData.asStableRef<SendChannel<String>>().get()
                ch.trySend(tokenPtr.toKString())
            }
        }

        try {
            localdoc_generate(currentSession, prompt, maxTokens, cCallback, channelRef.asCPointer())
        } finally {
            channelRef.dispose()
            close()
        }

        awaitClose { localdoc_stop(currentSession) }
    }.flowOn(Dispatchers.IO)

    actual fun stop() {
        sessionPtr?.let { localdoc_stop(it) }
    }

    actual fun unload() {
        sessionPtr?.let {
            localdoc_free(it)
            sessionPtr = null
        }
    }

    actual fun isLoaded(): Boolean = sessionPtr != null
}
