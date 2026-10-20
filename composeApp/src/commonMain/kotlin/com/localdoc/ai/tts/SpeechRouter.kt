package com.localdoc.ai.tts

/**
 * Единая точка озвучки для плеера и настроек: направляет реплики либо в системный
 * синтезатор ([system]), либо во встроенный Piper ([builtin]). Встроенный движок
 * выбирается engineId = [PiperSpeechEngine.ENGINE_ID]; voiceId тогда — id голосового пакета.
 */
class SpeechRouter(
    private val system: SpeechEngine,
    val builtin: PiperSpeechEngine
) : SpeechEngine {

    private var active: SpeechEngine = system

    val isBuiltinActive: Boolean get() = active === builtin

    /** Голоса системного синтезатора независимо от активного движка (после [prepareSystem]). */
    fun systemVoices(): List<Voice> = system.voices()

    override fun speak(text: String, onFinished: (Boolean) -> Unit) = active.speak(text, onFinished)

    override fun prefetch(text: String) = active.prefetch(text)

    override fun stop() {
        // Останавливаем оба: после переключения прежний движок мог ещё дочитывать фразу
        system.stop()
        builtin.stop()
    }

    override fun setRate(rate: Float) {
        system.setRate(rate)
        builtin.setRate(rate)
    }

    override fun setPitch(pitch: Float) {
        system.setPitch(pitch)
        builtin.setPitch(pitch)
    }

    override fun prepare(onReady: () -> Unit) = active.prepare(onReady)

    /**
     * Готовит системный синтезатор даже при активном встроенном: без этого список
     * системных движков пуст и к ним нельзя вернуться. Колбэк может не прийти,
     * если системного TTS на устройстве нет.
     */
    fun prepareSystem(onReady: () -> Unit) = system.prepare(onReady)

    override fun voices(): List<Voice> = active.voices()

    override fun setVoice(id: String?) = active.setVoice(id)

    override fun setLanguage(tag: String?) = system.setLanguage(tag)

    /** Только системные движки: встроенные голоса показываются отдельным разделом. */
    override fun engines(): List<TtsEngineInfo> = system.engines()

    override fun setEngine(id: String?) {
        val next = if (id == PiperSpeechEngine.ENGINE_ID) builtin else system
        if (next !== active) active.stop()
        active = next
        if (next === system) system.setEngine(id)
    }
}
