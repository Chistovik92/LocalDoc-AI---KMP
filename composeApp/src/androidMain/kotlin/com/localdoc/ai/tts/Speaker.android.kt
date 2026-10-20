package com.localdoc.ai.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.localdoc.ai.platform.AppContextHolder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Android: системный TextToSpeech. Работает с любым установленным движком —
 * Google TTS, открытыми RHVoice и sherpa-onnx (голоса Piper) — и их голосами.
 * Каждая реплика — отдельный utterance; результат приходит через UtteranceProgressListener.
 */
actual class Speaker actual constructor() : SpeechEngine {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var engineId: String? = null
    private var voiceId: String? = null
    private var languageTag: String? = null
    private var rate = 1f
    private var pitch = 1f
    private val callbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val pending = ArrayList<() -> Unit>()
    private val ids = AtomicLong()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finish(utteranceId, true)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId, false)
        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
        // stop() прерывает реплику намеренно — колбэк не нужен, контроллер уже сменил сессию
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { callbacks.remove(it) }
        }
    }

    private fun finish(id: String?, success: Boolean) {
        id?.let { callbacks.remove(it)?.invoke(success) }
    }

    private fun ensureInit() {
        if (tts != null) return
        ready = false
        val onInit = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(listener)
                applyLanguageAndVoice()
                ready = true
                val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
                queued.forEach { it() }
            } else {
                // Движок не поднялся: сообщаем об ошибке всем ожидающим репликам
                synchronized(pending) { pending.clear() }
                callbacks.keys.toList().forEach { finish(it, false) }
            }
        }
        tts = if (engineId != null) {
            TextToSpeech(AppContextHolder.appContext, onInit, engineId)
        } else {
            TextToSpeech(AppContextHolder.appContext, onInit)
        }
    }

    private fun applyLanguageAndVoice() {
        val engine = tts ?: return
        val selected = voiceId?.let { id -> runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == id } }
        if (selected != null) {
            engine.voice = selected
        } else {
            val result = engine.setLanguage(Locale.forLanguageTag(languageTag ?: "ru-RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
        }
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
    }

    private fun whenReady(action: () -> Unit) {
        ensureInit()
        if (ready) action() else synchronized(pending) { pending.add(action) }
    }

    override fun speak(text: String, onFinished: (Boolean) -> Unit) {
        val id = "localdoc_${ids.incrementAndGet()}"
        callbacks[id] = onFinished
        whenReady {
            val limit = TextToSpeech.getMaxSpeechInputLength()
            val result = tts?.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, Bundle(), id) ?: TextToSpeech.ERROR
            if (result != TextToSpeech.SUCCESS) finish(id, false)
        }
    }

    override fun stop() {
        synchronized(pending) { pending.clear() }
        callbacks.clear()
        tts?.stop()
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        tts?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch
        tts?.setPitch(pitch)
    }

    override fun prepare(onReady: () -> Unit) = whenReady(onReady)

    override fun voices(): List<Voice> {
        val engine = tts?.takeIf { ready } ?: return emptyList()
        return runCatching { engine.voices }.getOrNull().orEmpty()
            .map { v ->
                Voice(
                    id = v.name,
                    name = v.name,
                    language = v.locale.toLanguageTag(),
                    offline = !v.isNetworkConnectionRequired
                )
            }
            .sortedWith(compareBy({ !it.language.startsWith("ru") }, { !it.offline }, { it.language }, { it.name }))
    }

    override fun setVoice(id: String?) {
        voiceId = id
        if (ready) applyLanguageAndVoice()
    }

    override fun setLanguage(tag: String?) {
        languageTag = tag
        if (ready) applyLanguageAndVoice()
    }

    override fun engines(): List<TtsEngineInfo> {
        val engine = tts ?: return emptyList()
        return engine.engines.map { TtsEngineInfo(it.name, it.label) }
    }

    override fun setEngine(id: String?) {
        if (id == engineId) return
        engineId = id
        voiceId = null
        // Смена движка требует нового экземпляра TextToSpeech
        stop()
        tts?.shutdown()
        tts = null
        ensureInit()
    }

    actual fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
