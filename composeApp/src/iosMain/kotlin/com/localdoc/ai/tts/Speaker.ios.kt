package com.localdoc.ai.tts

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSLocale
import platform.Foundation.languageCode

@OptIn(ExperimentalForeignApi::class)
/** iOS: AVSpeechSynthesizer (системный офлайн-голос, в т.ч. русский). */
actual class Speaker actual constructor() {

    private val synthesizer = AVSpeechSynthesizer()

    actual fun speak(text: String) {
        val utterance = AVSpeechUtterance(string = text.take(4000))
        val lang = NSLocale.currentLocale.languageCode ?: "ru-RU"
        val voice = AVSpeechSynthesisVoice.voiceWithLanguage("ru-RU")
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(lang)
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
