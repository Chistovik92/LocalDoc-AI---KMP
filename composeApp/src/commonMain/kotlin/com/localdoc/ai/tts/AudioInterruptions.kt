package com.localdoc.ai.tts

/**
 * События звука вокруг озвучки: телефонные звонки, чужие плееры, наушники.
 *
 * Жизненный цикл: [start] — сессия чтения активна (в том числе на паузе: события
 * наушников должны приходить, чтобы продолжить чтение при подключении);
 * [stop] — чтение закрыто целиком (стоп, конец книги, ошибка, таймер сна).
 */
interface AudioInterruptions {
    /** [onInterrupted] — звук занят (звонок, другой плеер); [onRegained] — транзитное прерывание закончилось;
     *  [onHeadphonesDisconnected] — наушники отключены; [onHeadphonesConnected] — наушники подключены. */
    fun setListener(
        onInterrupted: () -> Unit = {},
        onRegained: () -> Unit = {},
        onHeadphonesDisconnected: () -> Unit = {},
        onHeadphonesConnected: () -> Unit = {}
    )

    /** Начать слушать события (идемпотентно); на Android заодно запросить аудиофокус. */
    fun start()

    /** Прекратить слушать и отпустить аудиофокус. */
    fun stop()
}

/** null — на платформе события не отслеживаются. */
expect fun createAudioInterruptions(): AudioInterruptions?
