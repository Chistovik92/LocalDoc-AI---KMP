package com.localdoc.ai.engine

import com.localdoc.ai.platform.SyncLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import android.util.Log
import android.os.Build
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
            // Android Emulator (особенно x86_64 через native-bridge) может загрузить
            // Vulkan-бэкенд, но аварийно завершает процесс внутри libomp при первом
            // llama_decode. На реальном устройстве сохраняем аппаратное ускорение.
            val effectiveGpuLayers = if (isEmulator()) 0 else gpuLayers
            Log.d("LocalDocDebug", "engine load path=$modelPath context=$contextSize gpu=$gpuLayers effectiveGpu=$effectiveGpuLayers emulator=${isEmulator()}")
            unload()
            val ptr = initNative(modelPath, contextSize, effectiveGpuLayers)
            sessionLock.withLock { sessionPtr = ptr }
            Log.d("LocalDocDebug", "engine load result=${ptr != 0L}")
            ptr != 0L
        }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

    actual fun generateStream(prompt: String, maxTokens: Int): Flow<String> = callbackFlow<String> {
        Log.d("LocalDocDebug", "engine generate start promptChars=${prompt.length} maxTokens=$maxTokens")
        val ptr = sessionLock.withLock { sessionPtr.also { if (it != 0L) activeGenerations++ } }
        if (ptr == 0L) {
            Log.e("LocalDocDebug", "engine generate rejected: model not loaded")
            check(false) { "Модель не загружена" }
        }

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
        Log.d("LocalDocDebug", "engine generate native returned status=$status")
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
