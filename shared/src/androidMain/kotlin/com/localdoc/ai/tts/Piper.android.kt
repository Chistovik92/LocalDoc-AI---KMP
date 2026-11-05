package com.localdoc.ai.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

actual fun createPiperBackend(): PiperBackend = SherpaPiperBackend()

actual fun createPcmOutput(): PcmOutput = AudioTrackOutput()

/** sherpa-onnx OfflineTts (JNI из AAR) с моделью VITS/Piper из файлов голоса. */
private class SherpaPiperBackend : PiperBackend {
    private var tts: OfflineTts? = null

    override fun load(paths: PiperVoicePaths): Boolean {
        release()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = paths.model, tokens = paths.tokens, dataDir = paths.dataDir),
                numThreads = THREADS,
                provider = "cpu"
            )
        )
        // Конструктор бросает IllegalArgumentException, если нативная модель не поднялась
        tts = runCatching { OfflineTts(assetManager = null, config = config) }.getOrNull()
        return tts != null
    }

    override fun generate(text: String, speed: Float): Pcm? {
        val engine = tts ?: return null
        val audio = runCatching { engine.generate(text = text, sid = 0, speed = speed) }.getOrNull() ?: return null
        return Pcm(audio.samples, audio.sampleRate)
    }

    override fun release() {
        tts?.release()
        tts = null
    }

    companion object {
        // Синтез идёт параллельно с воспроизведением — больших ядер на флагманах хватает с запасом
        private const val THREADS = 4
    }
}

/**
 * Вывод PCM через AudioTrack (float, моно). Трек пересоздаётся только при смене частоты.
 * Высота голоса — PlaybackParams.pitch: сам Piper её не регулирует.
 */
private class AudioTrackOutput : PcmOutput {
    private var track: AudioTrack? = null
    private var trackRate = 0
    // Прерванная фраза может ещё дописывать блок в другом потоке: её flush() не должен
    // стереть следующую — воспроизведения идут строго по очереди
    private val playLock = Mutex()

    override suspend fun play(pcm: Pcm, pitch: Float): Boolean = withContext(Dispatchers.IO) {
        playLock.withLock { playLocked(pcm, pitch) }
    }

    private suspend fun playLocked(pcm: Pcm, pitch: Float): Boolean {
        val audio = trackFor(pcm.sampleRate) ?: return false
        try {
            audio.playbackParams = PlaybackParams().setSpeed(1f).setPitch(pitch.coerceIn(0.5f, 2f))
            audio.play()
            val start = headPosition(audio)
            var offset = 0
            while (offset < pcm.samples.size) {
                coroutineContext.ensureActive()
                val count = minOf(CHUNK, pcm.samples.size - offset)
                val written = audio.write(pcm.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) return false
                offset += written
            }
            // write() возвращается, когда данные в буфере, а не когда прозвучали — ждём доигрывания
            val frames = pcm.samples.size.toLong()
            var last = -1L
            var stalledMs = 0L
            while (true) {
                val played = headPosition(audio) - start
                if (played >= frames) break
                if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) return false
                // Позиция перестала расти (устройство вывода сменилось) — не висим вечно
                stalledMs = if (played == last) stalledMs + POLL_MS else 0L
                if (stalledMs >= STALL_TIMEOUT_MS) break
                last = played
                delay(POLL_MS)
            }
            return true
        } catch (e: CancellationException) {
            // stop() или следующая фраза: глушим сразу, остаток буфера не доигрываем
            silence(audio)
            throw e
        } catch (e: IllegalStateException) {
            return false
        }
    }

    private fun headPosition(audio: AudioTrack): Long = audio.playbackHeadPosition.toLong() and UINT_MASK

    private fun silence(audio: AudioTrack) {
        runCatching {
            audio.pause()
            audio.flush()
        }
    }

    private fun trackFor(sampleRate: Int): AudioTrack? {
        track?.takeIf { trackRate == sampleRate && it.state == AudioTrack.STATE_INITIALIZED }?.let { return it }
        release()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) return null
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * BUFFER_FACTOR)
                .build()
        }.getOrNull()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        track = created
        trackRate = if (created != null) sampleRate else 0
        return created
    }

    override fun release() {
        track?.release()
        track = null
        trackRate = 0
    }

    companion object {
        private const val CHUNK = 4096
        private const val BUFFER_FACTOR = 4
        private const val POLL_MS = 20L
        private const val STALL_TIMEOUT_MS = 1_000L
        // playbackHeadPosition — беззнаковое 32-битное значение
        private const val UINT_MASK = 0xFFFF_FFFFL
    }
}
