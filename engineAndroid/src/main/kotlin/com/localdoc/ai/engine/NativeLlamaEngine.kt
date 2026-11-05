package com.localdoc.ai.engine

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI-мост к llama.cpp (Vulkan на Adreno). Полностью автономен: не зависит от `shared`
 * (shared сам зависит от engineAndroid — обратная зависимость создала бы цикл).
 *
 * Код результата генерации не локализуется здесь (нет доступа к L10n) — вместо этого
 * выбрасывается [EngineException] с сырым кодом `localdoc_generate`, который `shared`
 * оборачивает в понятное пользователю сообщение через GenerationStatus.
 */
class NativeLlamaEngine {

    /** Код результата localdoc_generate (см. cpp_core/bridge/localdoc_c_api.h). */
    class EngineException(val status: Int) : IllegalStateException("Ошибка генерации: $status")

    companion object {
        init {
            System.loadLibrary("localdoc_jni")
        }
    }

    @Volatile
    private var sessionPtr: Long = 0

    // Защищает sessionPtr от гонки «stop() из обработчика отмены» ↔ «unload() освобождает сессию»
    private val sessionLock = ReentrantLock()

    // Число генераций, получивших указатель и (возможно) вошедших в нативный вызов.
    // unload() освобождает сессию только когда их не осталось. Защищено sessionLock.
    private var activeGenerations = 0

    /** Вызывается из JNI: токен приходит байтами стандартного UTF-8. */
    interface TokenCallback {
        fun onToken(utf8: ByteArray)
    }

    private external fun initNative(modelPath: String, nCtx: Int, nGpuLayers: Int, nThreads: Int): Long
    private external fun generateNative(sessionPtr: Long, promptUtf8: ByteArray, maxTokens: Int, callback: TokenCallback): Int
    private external fun stopNative(sessionPtr: Long)
    private external fun isGeneratingNative(sessionPtr: Long): Boolean
    private external fun freeNative(sessionPtr: Long)

    /** Загружает GGUF-модель в память (mmap). Возвращает true при успехе. */
    suspend fun load(modelPath: String, contextSize: Int, gpuLayers: Int): Boolean =
        withContext(Dispatchers.IO) {
            // Android Emulator (особенно x86_64 через native-bridge) может загрузить
            // Vulkan-бэкенд, но аварийно завершает процесс внутри libomp при первом
            // llama_decode. На реальном устройстве сохраняем аппаратное ускорение.
            val effectiveGpuLayers = if (isEmulator()) 0 else gpuLayers
            val effectiveThreads = if (isEmulator()) 1 else Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            Log.d("LocalDocDebug", "engine load path=$modelPath context=$contextSize gpu=$gpuLayers effectiveGpu=$effectiveGpuLayers threads=$effectiveThreads emulator=${isEmulator()}")
            unload()
            val ptr = initNative(modelPath, contextSize, effectiveGpuLayers, effectiveThreads)
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

    /** Потоковая генерация: на каждый валидный UTF-8 токен — элемент Flow. */
    fun generateStream(prompt: String, maxTokens: Int): Flow<String> = callbackFlow<String> {
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
        if (status != 0) throw EngineException(status)
        close()
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    fun stop() {
        sessionLock.withLock { if (sessionPtr != 0L) stopNative(sessionPtr) }
    }

    /** Останавливает генерацию, только если сессия ptr ещё не выгружена. */
    private fun stopIfCurrent(ptr: Long) {
        sessionLock.withLock { if (sessionPtr == ptr) stopNative(ptr) }
    }

    fun unload() {
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

    fun isLoaded(): Boolean = sessionPtr != 0L
}

private const val UNLOAD_POLL_MS = 5L
