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

    // Число генераций, получивших указатель и (возможно) вошедших в нативный вызов.
    // unload() освобождает сессию только когда их не осталось. Защищено sessionLock.
    private var activeGenerations = 0

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
        val ptr = sessionLock.withLock { sessionPtr.also { if (it != 0L) activeGenerations++ } }
        check(ptr != 0L) { "Модель не загружена" }

        // Всё после захвата указателя — под finally, иначе счётчик «утечёт» и unload() зависнет
        val status = try {
            // Отмена сборщика (уход с экрана, кнопка «Стоп») прерывает генерацию в C-ядре
            invokeOnClose { stopIfCurrent(ptr) }

            val callback = object : TokenCallback {
                override fun onToken(utf8: ByteArray) {
                    trySend(utf8.decodeToString())
                }
            }
            // Нулевой байт в конце — C-строка для localdoc_generate
            val promptBytes = prompt.encodeToByteArray() + 0.toByte()
            generateNative(ptr, promptBytes, maxTokens, callback)
        } finally {
            sessionLock.withLock { activeGenerations-- }
        }
        GenerationStatus.ensureOk(status)
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
        // Генерация могла получить указатель, но ещё не войти в C-ядро: ждём её выхода,
        // повторяя stop (он действует только на уже начатую генерацию)
        while (sessionLock.withLock { activeGenerations } > 0) {
            stopNative(ptr)
            Thread.sleep(UNLOAD_POLL_MS)
        }
        freeNative(ptr)
    }

    actual fun isLoaded(): Boolean = sessionPtr != 0L
}

private const val UNLOAD_POLL_MS = 5L
