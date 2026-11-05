package com.localdoc.ai.tts

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.darwin.NSObject

/**
 * iOS: AVSpeechSynthesizer (офлайн-голоса системы; дополнительные голоса, в том числе
 * Enhanced/Premium, загружаются в «Настройки → Универсальный доступ → Устный контент»).
 */
@OptIn(ExperimentalForeignApi::class)
actual class Speaker actual constructor() : SpeechEngine {

    private val synthesizer = AVSpeechSynthesizer()
    private var current: AVSpeechUtterance? = null
    private var currentCallback: ((Boolean) -> Unit)? = null
    private var voiceId: String? = null
    private var languageTag: String? = null
    private var rate = 1f
    private var pitch = 1f
    private var volume = 1f

    // Делегат держим сильной ссылкой: свойство delegate у синтезатора — weak
    private val delegate = SynthDelegate { utterance -> onUtteranceFinished(utterance) }

    init {
        synthesizer.delegate = delegate
    }

    private fun onUtteranceFinished(utterance: AVSpeechUtterance) {
        if (utterance !== current) return
        val callback = currentCallback
        current = null
        currentCallback = null
        callback?.invoke(true)
    }

    override fun speak(text: String, onFinished: (Boolean) -> Unit) {
        if (text.isBlank()) {
            onFinished(false)
            return
        }
        val utterance = AVSpeechUtterance(string = text)
        utterance.voice = voiceId?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) }
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(languageTag ?: "ru-RU")
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(AVSpeechSynthesisVoice.currentLanguageCode())
        // Базовая скорость системы × множитель пользователя
        utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * rate).coerceIn(0.1f, 1f)
        utterance.pitchMultiplier = pitch.coerceIn(0.5f, 2f)
        // Громкость реплики: затухание таймера сна идёт по предложениям
        utterance.volume = volume
        // speakUtterance ставит в очередь — прежнюю реплику прерываем, чтобы голоса не накладывались
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        current = utterance
        currentCallback = onFinished
        synthesizer.speakUtterance(utterance)
    }

    override fun stop() {
        current = null
        currentCallback = null
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun setRate(rate: Float) {
        this.rate = rate
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch
    }

    override fun prepare(onReady: () -> Unit) = onReady()

    override fun setLanguage(tag: String?) {
        languageTag = tag
    }

    override fun voices(): List<Voice> =
        AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .map { Voice(id = it.identifier, name = it.name, language = it.language) }
            .sortedWith(compareBy({ !it.language.startsWith("ru") }, { it.language }, { it.name }))

    override fun setVoice(id: String?) {
        voiceId = id
    }

    override fun engines(): List<TtsEngineInfo> = listOf(TtsEngineInfo("system", "Системный синтезатор iOS"))

    override fun setEngine(id: String?) = Unit

    actual fun shutdown() {
        stop()
    }

    private class SynthDelegate(
        private val onFinish: (AVSpeechUtterance) -> Unit
    ) : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            onFinish(didFinishSpeechUtterance)
        }

        // Прерывание системой (звонок, Siri) — иначе плеер «играет» в тишине
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            onFinish(didCancelSpeechUtterance)
        }
    }
}
