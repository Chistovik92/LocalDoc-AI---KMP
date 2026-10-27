package com.localdoc.ai.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.localdoc.ai.platform.AndroidIntents
import com.localdoc.ai.platform.AppContextHolder

actual fun createPhraseRecognizer(): PhraseRecognizer = AndroidPhraseRecognizer()

actual fun requestMicrophone(onResult: (Boolean) -> Unit) {
    val context = AppContextHolder.appContext
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        onResult(true)
    } else {
        AndroidIntents.launchMicrophonePermission(onResult)
    }
}

/**
 * Android 13+: распознавание речи на самом устройстве (`createOnDeviceSpeechRecognizer`, пакет языка ставится системой).
 * На более старых версиях системный распознаватель мог бы уйти в сеть, поэтому там функция недоступна.
 * Распознаватель отдаёт одну фразу за сеанс, поэтому после каждой фразы или паузы сеанс запускается заново.
 */
private class AndroidPhraseRecognizer : PhraseRecognizer {
    private val context = AppContextHolder.appContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var onPhrase: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}

    override val isOfflineAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(onPhrase: (String) -> Unit, onError: (String) -> Unit) {
        this.onPhrase = onPhrase
        this.onError = onError
        main.post {
            if (running) return@post
            if (!isOfflineAvailable) {
                onError("Офлайн-распознавание речи недоступно на этом устройстве (нужен Android 13+ и пакет языка)")
                return@post
            }
            running = true
            listen()
        }
    }

    override fun stop() {
        main.post {
            running = false
            main.removeCallbacksAndMessages(null)
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun listen() {
        if (!running) return
        recognizer?.destroy()
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
        if (created == null) {
            fail("Офлайн-распознавание речи недоступно")
            return
        }
        recognizer = created
        created.setRecognitionListener(listener)
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
    }

    private fun restart(delayMs: Long) {
        if (running) main.postDelayed({ listen() }, delayMs)
    }

    private fun fail(message: String) {
        running = false
        recognizer?.destroy()
        recognizer = null
        onError(message)
    }

    private fun deliver(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPhrase)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            deliver(results)
            restart(RESTART_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) = deliver(partialResults)

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Нет разрешения на микрофон")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    fail("Для русского языка нет офлайн-пакета распознавания речи — установите его в настройках Android")
                // Тишина и «ничего не распознано» — обычное дело: слушаем дальше с паузой, чтобы не крутить процессор
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(RESTART_MS)
                else -> restart(BUSY_RESTART_MS)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val RESTART_MS = 300L
        const val BUSY_RESTART_MS = 1_500L
    }
}
