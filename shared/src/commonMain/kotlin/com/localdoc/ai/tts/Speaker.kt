package com.localdoc.ai.tts

/**
 * Локальный синтезатор речи без интернета:
 * Android: системный TextToSpeech (любой установленный движок — Google, RHVoice, sherpa-onnx);
 * iOS: AVSpeechSynthesizer (системные и загруженные голоса, включая Enhanced/Premium).
 */
expect class Speaker() : SpeechEngine {
    fun shutdown()
}
