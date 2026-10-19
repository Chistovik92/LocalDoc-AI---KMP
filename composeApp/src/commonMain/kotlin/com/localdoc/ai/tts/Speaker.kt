package com.localdoc.ai.tts

/**
 * Локальный синтезатор речи без интернета:
 * Android: системный TextToSpeech; iOS: AVSpeechSynthesizer.
 */
expect class Speaker() {
    fun speak(text: String)
    fun stop()
    fun shutdown()
}
