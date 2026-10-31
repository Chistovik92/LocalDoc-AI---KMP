package com.localdoc.ai.platform

/**
 * Короткое аудио (сэмплы голосов): платформенный декодер (MP3/WAV) с прямым
 * выводом в устройство. Реализации не зависят от состояния основного плеера.
 */
interface SampleAudio {
    /** true — воспроизведение началось. */
    fun play(url: String): Boolean

    /** Остановить текущее воспроизведение (если играет). */
    fun stop()

    fun release()
}

expect fun createSampleAudio(): SampleAudio
