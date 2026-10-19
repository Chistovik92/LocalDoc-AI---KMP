package com.localdoc.ai.tts

import android.speech.tts.TextToSpeech
import com.localdoc.ai.platform.AppContextHolder
import java.util.Locale

/** Android: системный TextToSpeech (работает офлайн с установленными голосами). */
actual class Speaker actual constructor() {

    private var tts: TextToSpeech? = null
    private var pendingText: String? = null

    private fun ensureInit() {
        if (tts != null) return
        tts = TextToSpeech(AppContextHolder.appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.forLanguageTag("ru-RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.getDefault())
                }
                pendingText?.let { queued ->
                    pendingText = null
                    tts?.speak(queued.take(4000), TextToSpeech.QUEUE_FLUSH, null, "localdoc_utterance")
                }
            }
        }
    }

    actual fun speak(text: String) {
        ensureInit()
        tts?.let { engine ->
            // Если движок уже готов — говорим сразу, иначе текст уйдёт после инициализации
            if (engine.isLanguageAvailable(Locale.getDefault()) != TextToSpeech.LANG_NOT_SUPPORTED &&
                engine.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "localdoc_utterance") < 0
            ) {
                pendingText = text
            }
        } ?: run { pendingText = text }
    }

    actual fun stop() {
        tts?.stop()
    }

    actual fun shutdown() {
        tts?.shutdown()
        tts = null
        pendingText = null
    }
}
