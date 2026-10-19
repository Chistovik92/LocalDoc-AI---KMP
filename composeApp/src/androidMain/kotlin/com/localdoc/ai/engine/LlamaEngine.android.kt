package com.localdoc.ai.engine

import com.localdoc.ai.platform.SyncLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
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

    @Volatile
    private var sessionPtr: Long = 0

    // Защищает sessionPtr от гонки «stop() из обработчика отмены» ↔ «unload() освобождает сессию»
    private val sessionLock = SyncLock()

    /** Вызывается из JNI: токен приходит байтами стандартного UTF-8. */
    interface TokenCallback {
        fun onToken(utf8: ByteArray)
    }

    private external fun initNative(modelPath: String, nCtx: Int, nGpuLayers: Int): Long
    private external fun generateNative(sessionPtr: Long, promptUtf8: ByteArray, maxTokens: Int, callback: TokenCallback): Int
    private external fun stopNative(sessionPtr: Long)
    private external fun isGeneratingNative(sessionPtr: Long): Boolean
    private external fun freeNative(sessionPtr: Long)

    actual suspend fun load(modelPath: String, contextSize: Int, gpuLayers: Int): Boolean =
        withContext(Dispatchers.IO) {
            unload()
            val ptr = initNative(modelPath, contextSize, gpuLayers)
            sessionLock.withLock { sessionPtr = ptr }
            ptr != 0L
        }

    actual fun generateStream(prompt: String, maxTokens: Int): Flow<String> = callbackFlow<String> {
        val ptr = sessionPtr
        check(ptr != 0L) { "Модель не загружена" }

        // Отмена сборщика (уход с экрана, кнопка «Стоп») прерывает генерацию в C-ядре
        invokeOnClose { stopIfCurrent(ptr) }

        val callback = object : TokenCallback {
            override fun onToken(utf8: ByteArray) {
                trySend(utf8.decodeToString())
            }
        }

        // Нулевой байт в конце — C-строка для localdoc_generate
        val promptBytes = prompt.encodeToByteArray() + 0.toByte()
        GenerationStatus.ensureOk(generateNative(ptr, promptBytes, maxTokens, callback))
        close()
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    actual fun stop() {
        sessionLock.withLock { if (sessionPtr != 0L) stopNative(sessionPtr) }
    }

    /** Останавливает генерацию, только если сессия ptr ещё не выгружена. */
    private fun stopIfCurrent(ptr: Long) {
        sessionLock.withLock { if (sessionPtr == ptr) stopNative(ptr) }
    }

    actual fun unload() {
        val ptr = sessionLock.withLock {
            sessionPtr.also { sessionPtr = 0L }
        }
        if (ptr == 0L) return
        // freeNative сам прерывает и дожидается завершения активной генерации
        freeNative(ptr)
    }

    actual fun isLoaded(): Boolean = sessionPtr != 0L
}
