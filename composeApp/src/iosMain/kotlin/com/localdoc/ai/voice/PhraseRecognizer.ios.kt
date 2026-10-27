package com.localdoc.ai.voice

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

actual fun createPhraseRecognizer(): PhraseRecognizer = IosPhraseRecognizer()

actual fun requestMicrophone(onResult: (Boolean) -> Unit) {
    AVAudioSession.sharedInstance().requestRecordPermission { granted ->
        dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
    }
}

/**
 * iOS: `SFSpeechRecognizer` с `requiresOnDeviceRecognition` — звук не покидает устройство. Если для русского языка
 * нет загруженной модели (`supportsOnDeviceRecognition == false`), функция недоступна, в сеть приложение не ходит.
 * Не проверялось на устройстве: сборка без macOS невозможна.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosPhraseRecognizer : PhraseRecognizer {
    private val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = "ru-RU"))
    private val engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var running = false
    private var onPhrase: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}

    override val isOfflineAvailable: Boolean
        get() = recognizer.isAvailable() && recognizer.supportsOnDeviceRecognition

    override fun start(onPhrase: (String) -> Unit, onError: (String) -> Unit) {
        this.onPhrase = onPhrase
        this.onError = onError
        if (running) return
        if (!isOfflineAvailable) {
            onError("Для русского языка нет загруженной модели распознавания речи на устройстве")
            return
        }
        SFSpeechRecognizer.requestAuthorization { status ->
            dispatch_async(dispatch_get_main_queue()) {
                if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    running = true
                    begin()
                } else {
                    onError("Нет разрешения на распознавание речи")
                }
            }
        }
    }

    override fun stop() {
        running = false
        end()
        // Возвращаем категорию только для воспроизведения: микрофон больше не нужен
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
    }

    private fun begin() {
        if (!running) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth,
            error = null
        )
        session.setActive(true, error = null)

        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.requiresOnDeviceRecognition = true
        request = req

        val input = engine.inputNode
        input.installTapOnBus(0u, 1024u, input.outputFormatForBus(0u)) { buffer, _ ->
            buffer?.let { req.appendAudioPCMBuffer(it) }
        }
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            running = false
            end()
            onError("Не удалось включить микрофон")
            return
        }
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            dispatch_async(dispatch_get_main_queue()) {
                result?.bestTranscription?.formattedString?.takeIf { it.isNotBlank() }?.let(onPhrase)
                // Сеанс закончился (пауза в речи, лимит времени) — открываем новый, пока функция включена
                if (error != null || result?.isFinal() == true) {
                    end()
                    // Пауза перед новым сеансом: без неё тишина превращалась бы в плотный цикл и расход батареи
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, RESTART_NS), dispatch_get_main_queue()) { begin() }
                }
            }
        }
    }

    private fun end() {
        task?.cancel()
        task = null
        request?.endAudio()
        request = null
        if (engine.isRunning()) {
            engine.stop()
            engine.inputNode.removeTapOnBus(0u)
        }
    }

    private companion object {
        const val RESTART_NS = 700_000_000L
    }
}
