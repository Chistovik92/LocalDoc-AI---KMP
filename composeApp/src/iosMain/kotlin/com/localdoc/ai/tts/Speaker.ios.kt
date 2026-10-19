package com.localdoc.ai.tts

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVSpeechBoundaryImmediate
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechVoice
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode

@OptIn(ExperimentalForeignApi::class)
/** iOS: AVSpeechSynthesizer (системный офлайн-голос, в т.ч. русский). */
actual class Speaker actual constructor() {

    private val synthesizer = AVSpeechSynthesizer()

    actual fun speak(text: String) {
        val utterance = AVSpeechUtterance(string = text.take(4000))
        val voice = AVSpeechVoice.voiceWithLanguage("ru-RU")
            ?: AVSpeechVoice.voiceWithLanguage(NSLocale.currentLocale.objectForKey(NSLocaleLanguageCode) as? String)
        voice?.let { utterance.voice = it }
        utterance.rate = 0.5f
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)
        synthesizer.speakUtterance(utterance)
    }

    actual fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)
    }

    actual fun shutdown() {
        stop()
    }
}
