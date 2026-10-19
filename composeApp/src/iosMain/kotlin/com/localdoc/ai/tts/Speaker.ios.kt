package com.localdoc.ai.tts

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance

@OptIn(ExperimentalForeignApi::class)
/** iOS: AVSpeechSynthesizer (системный офлайн-голос, в т.ч. русский). */
actual class Speaker actual constructor() {

    private val synthesizer = AVSpeechSynthesizer()

    actual fun speak(text: String) {
        val utterance = AVSpeechUtterance(string = text.take(4000))
        // Предпочитаем русский голос, иначе — голос системного языка
        val voice = AVSpeechSynthesisVoice.voiceWithLanguage("ru-RU")
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(AVSpeechSynthesisVoice.currentLanguageCode())
        utterance.voice = voice
        utterance.rate = 0.5f
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        synthesizer.speakUtterance(utterance)
    }

    actual fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    actual fun shutdown() {
        stop()
    }
}
