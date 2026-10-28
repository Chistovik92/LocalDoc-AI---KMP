package com.localdoc.ai.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Синтезированная речь: моно float PCM в диапазоне [-1, 1]. */
class Pcm(val samples: FloatArray, val sampleRate: Int)

/** Нейросетевой синтезатор (sherpa-onnx OfflineTts). Все вызовы блокирующие — только из фонового потока. */
interface PiperBackend {
    /** Загружает голос; false — файлы повреждены или модель не поднялась. */
    fun load(paths: PiperVoicePaths): Boolean
    /** null — синтез не удался. [speed] > 1 — быстрее. */
    fun generate(text: String, speed: Float): Pcm?
    fun release()
}

/** Вывод PCM на динамик (AudioTrack / AVAudioEngine). */
interface PcmOutput {
    /** Возвращается, когда звук доиграл (true) или вывод сломался (false). Отмена корутины глушит звук. */
    suspend fun play(pcm: Pcm, pitch: Float): Boolean
    fun release()
}

expect fun createPiperBackend(): PiperBackend
expect fun createPcmOutput(): PcmOutput

/**
 * Встроенный движок озвучки на голосах Piper: ничего не нужно ставить отдельно.
 *
 * Пока звучит текущее предложение, следующее уже синтезируется ([prefetch]) —
 * между фразами нет паузы на генерацию. onFinished вызывается ровно один раз
 * на реплику, кроме прерывания через stop() или новую реплику.
 */
class PiperSpeechEngine(
    private val backend: PiperBackend,
    private val output: PcmOutput,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    /** Пути к файлам голоса; null — голос не установлен. */
    private val resolvePaths: (String) -> PiperVoicePaths?
) : SpeechEngine {

    private val synthLock = Mutex()
    private val cache = LinkedHashMap<CacheKey, Pcm>()
    private var loadedVoice: String? = null
    private var voiceId: String? = null
    private var rate = 1f
    private var pitch = 1f
    private var volume = 1f
    private var speaking: Job? = null
    private var prefetching: Job? = null

    private data class CacheKey(val voice: String, val text: String, val rate: Float)

    override fun speak(text: String, onFinished: (Boolean) -> Unit) {
        speaking?.cancel()
        val voice = voiceId
        val speed = rate
        val pitchNow = pitch
        if (text.isBlank() || voice == null) {
            onFinished(false)
            return
        }
        speaking = scope.launch(worker) {
            val result = try {
                val pcm = synthesize(voice, text, speed)
                pcm != null && output.play(withVolume(pcm), pitchNow)
            } catch (e: CancellationException) {
                // Прерывание через stop()/новую реплику: колбэк не нужен
                throw e
            } catch (e: Exception) {
                false
            }
            onFinished(result)
        }
    }

    override fun prefetch(text: String) {
        val voice = voiceId ?: return
        if (text.isBlank()) return
        val speed = rate
        prefetching?.cancel()
        prefetching = scope.launch(worker) {
            runCatching { synthesize(voice, text, speed) }
                .exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    /** Синтез под замком: один нативный вызов за раз, повторный текст — из кэша. */
    private suspend fun synthesize(voice: String, text: String, speed: Float): Pcm? = synthLock.withLock {
        val key = CacheKey(voice, text, speed)
        // Запись остаётся в кэше: повтор фразы («Конец главы.», перемотка назад) — без синтеза
        cache[key]?.let { return@withLock it }
        if (!ensureLoaded(voice)) return@withLock null
        val pcm = backend.generate(text, speed)?.takeIf { it.samples.isNotEmpty() } ?: return@withLock null
        cache[key] = pcm
        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        pcm
    }

    private fun ensureLoaded(voice: String): Boolean {
        if (loadedVoice == voice) return true
        backend.release()
        loadedVoice = null
        cache.clear()
        val paths = resolvePaths(voice) ?: return false
        if (!backend.load(paths)) return false
        loadedVoice = voice
        return true
    }

    override fun stop() {
        speaking?.cancel()
        speaking = null
    }

    override fun setRate(rate: Float) {
        this.rate = rate
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    /** Громкость применяется к копии звука: кэш хранит запись с обычной громкостью. */
    private fun withVolume(pcm: Pcm): Pcm {
        val v = volume
        return if (v >= 1f) pcm else Pcm(FloatArray(pcm.samples.size) { pcm.samples[it] * v }, pcm.sampleRate)
    }

    override fun prepare(onReady: () -> Unit) = onReady()

    override fun voices(): List<Voice> =
        PiperVoiceCatalog.voices
            .filter { resolvePaths(it.id) != null }
            .map { Voice(id = it.id, name = it.name, language = it.language) }

    override fun setVoice(id: String?) {
        voiceId = id
    }

    override fun engines(): List<TtsEngineInfo> = listOf(TtsEngineInfo(ENGINE_ID, "Встроенный (Piper)"))

    override fun setEngine(id: String?) = Unit

    /** Освобождает модель (например, после удаления голоса). */
    fun unload() {
        stop()
        prefetching?.cancel()
        scope.launch(worker) {
            synthLock.withLock {
                backend.release()
                loadedVoice = null
                cache.clear()
            }
        }
    }

    companion object {
        /** engineId в настройках озвучки, при котором читает встроенный движок. */
        const val ENGINE_ID = "localdoc.piper"
        private const val CACHE_SIZE = 3
    }
}
