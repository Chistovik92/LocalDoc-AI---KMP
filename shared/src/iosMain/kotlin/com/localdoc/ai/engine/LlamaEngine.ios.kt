package com.localdoc.ai.engine

import cnames.structs.LocalDocSession
import com.localdoc.ai.cinterop.localdoc_free
import com.localdoc.ai.cinterop.localdoc_generate
import com.localdoc.ai.cinterop.localdoc_init
import com.localdoc.ai.cinterop.localdoc_stop
import com.localdoc.ai.platform.SyncLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.posix.usleep

/** iOS-реализация: прямой вызов C-ядра llama.cpp через cinterop (Metal, без JNI). */
@OptIn(ExperimentalForeignApi::class)
actual class LlamaEngine actual constructor() {

    // Непрозрачная C-структура импортируется cinterop как cnames.structs.LocalDocSession
    private var sessionPtr: CPointer<LocalDocSession>? = null

    // Защищает sessionPtr от гонки «stop() из обработчика отмены» ↔ «unload() освобождает сессию»
    private val sessionLock = SyncLock()

    // Число генераций, получивших указатель и (возможно) вошедших в нативный вызов.
    // unload() освобождает сессию только когда их не осталось. Защищено sessionLock.
    private var activeGenerations = 0

    actual suspend fun load(modelPath: String, contextSize: Int, gpuLayers: Int): Boolean =
        withContext(Dispatchers.IO) {
            unload()
            val session = localdoc_init(modelPath, contextSize, gpuLayers)
            sessionLock.withLock { sessionPtr = session }
            session != null
        }

    actual fun generateStream(prompt: String, maxTokens: Int): Flow<String> = callbackFlow<String> {
        val currentSession = sessionLock.withLock { sessionPtr?.also { activeGenerations++ } }
            ?: error("Модель не загружена")

        // Всё после захвата указателя — под finally, иначе счётчик «утечёт» и unload() зависнет
        val status = try {
            // Отмена сборщика (уход с экрана, кнопка «Стоп») прерывает генерацию в C-ядре
            invokeOnClose { stopIfCurrent(currentSession) }

            // Оборачиваем канал в StableRef, чтобы безопасно передать его в C-коллбэк
            val channelRef = StableRef.create<SendChannel<String>>(channel)
            val cCallback = staticCFunction { tokenPtr: CPointer<ByteVar>?, userData: COpaquePointer? ->
                if (tokenPtr != null && userData != null) {
                    userData.asStableRef<SendChannel<String>>().get().trySend(tokenPtr.toKString())
                }
            }
            try {
                localdoc_generate(currentSession, prompt, maxTokens, cCallback, channelRef.asCPointer())
            } finally {
                channelRef.dispose()
            }
        } finally {
            sessionLock.withLock { activeGenerations-- }
        }
        GenerationStatus.ensureOk(status)
        close()
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    actual fun stop() {
        sessionLock.withLock { sessionPtr?.let { localdoc_stop(it) } }
    }

    /** Останавливает генерацию, только если сессия ещё не выгружена. */
    private fun stopIfCurrent(session: CPointer<LocalDocSession>) {
        sessionLock.withLock { if (sessionPtr == session) localdoc_stop(session) }
    }

    actual fun unload() {
        val session = sessionLock.withLock { sessionPtr.also { sessionPtr = null } } ?: return
        // Генерация могла получить указатель, но ещё не войти в C-ядро: ждём её выхода,
        // повторяя stop (он действует только на уже начатую генерацию)
        while (sessionLock.withLock { activeGenerations } > 0) {
            localdoc_stop(session)
            usleep(UNLOAD_POLL_US)
        }
        localdoc_free(session)
    }

    actual fun isLoaded(): Boolean = sessionLock.withLock { sessionPtr != null }
}

private const val UNLOAD_POLL_US = 5_000u
