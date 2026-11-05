@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.tts

import cnames.structs.SherpaOnnxOfflineTts
import com.localdoc.ai.sherpa.SherpaOnnxCreateOfflineTts
import com.localdoc.ai.sherpa.SherpaOnnxDestroyOfflineTts
import com.localdoc.ai.sherpa.SherpaOnnxDestroyOfflineTtsGeneratedAudio
import com.localdoc.ai.sherpa.SherpaOnnxGenerationConfig
import com.localdoc.ai.sherpa.SherpaOnnxOfflineTtsConfig
import com.localdoc.ai.sherpa.SherpaOnnxOfflineTtsGenerateWithConfig
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioUnitTimePitch
import platform.posix.memcpy
import platform.posix.memset
import kotlin.math.log2

actual fun createPiperBackend(): PiperBackend = SherpaPiperBackend()

actual fun createPcmOutput(): PcmOutput = AvAudioOutput()

/** sherpa-onnx OfflineTts через C API (SherpaOnnxC.xcframework) с моделью VITS/Piper. */
private class SherpaPiperBackend : PiperBackend {
    private var tts: CPointer<SherpaOnnxOfflineTts>? = null

    override fun load(paths: PiperVoicePaths): Boolean {
        release()
        tts = memScoped {
            val config = alloc<SherpaOnnxOfflineTtsConfig>()
            // Нулевые поля C API заменяет значениями по умолчанию (SHERPA_ONNX_OR)
            memset(config.ptr, 0, sizeOf<SherpaOnnxOfflineTtsConfig>().convert())
            config.model.vits.model = paths.model.cstr.ptr
            config.model.vits.tokens = paths.tokens.cstr.ptr
            config.model.vits.data_dir = paths.dataDir.cstr.ptr
            config.model.vits.noise_scale = NOISE_SCALE
            config.model.vits.noise_scale_w = NOISE_SCALE_W
            config.model.vits.length_scale = 1f
            config.model.num_threads = THREADS
            config.model.provider = "cpu".cstr.ptr
            config.max_num_sentences = 1
            config.silence_scale = SILENCE_SCALE
            // Строки живут до конца memScoped — C-ядро копирует их при создании
            SherpaOnnxCreateOfflineTts(config.ptr)
        }
        return tts != null
    }

    override fun generate(text: String, speed: Float): Pcm? {
        val engine = tts ?: return null
        val audio = memScoped {
            val config = alloc<SherpaOnnxGenerationConfig>()
            memset(config.ptr, 0, sizeOf<SherpaOnnxGenerationConfig>().convert())
            config.sid = 0
            config.speed = speed
            config.silence_scale = SILENCE_SCALE
            SherpaOnnxOfflineTtsGenerateWithConfig(engine, text, config.ptr, null, null)
        } ?: return null
        try {
            val result = audio.pointed
            val count = result.n
            val samples = result.samples ?: return null
            if (count <= 0) return null
            val out = FloatArray(count)
            out.usePinned { memcpy(it.addressOf(0), samples, (count.toLong() * Float.SIZE_BYTES).convert()) }
            return Pcm(out, result.sample_rate)
        } finally {
            SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio)
        }
    }

    override fun release() {
        tts?.let { SherpaOnnxDestroyOfflineTts(it) }
        tts = null
    }

    companion object {
        private const val THREADS = 4
        private const val NOISE_SCALE = 0.667f
        private const val NOISE_SCALE_W = 0.8f
        private const val SILENCE_SCALE = 0.2f
    }
}

/**
 * Вывод PCM: AVAudioEngine → AVAudioPlayerNode → AVAudioUnitTimePitch (высота голоса) → микшер.
 * Буферы встают в очередь плеера одна за другой — между фразами нет щелчков и пауз.
 * Категорию аудиосессии (Playback, фон) выставляет SystemPlayer.
 */
private class AvAudioOutput : PcmOutput {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val timePitch = AVAudioUnitTimePitch()
    private var format: AVAudioFormat? = null
    private val playLock = Mutex()

    init {
        engine.attachNode(player)
        engine.attachNode(timePitch)
    }

    /** Граф перестраивается только при смене частоты; движок перезапускается после прерываний. */
    private fun ensureGraph(sampleRate: Int): AVAudioFormat? {
        val current = format?.takeIf { it.sampleRate == sampleRate.toDouble() }
        val fmt = current ?: AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 1u).also {
            engine.stop()
            engine.disconnectNodeOutput(player)
            engine.disconnectNodeOutput(timePitch)
            engine.connect(player, to = timePitch, format = it)
            engine.connect(timePitch, to = engine.mainMixerNode, format = it)
            format = it
        }
        if (!engine.running) {
            engine.prepare()
            if (!engine.startAndReturnError(null)) return null
        }
        return fmt
    }

    override suspend fun play(pcm: Pcm, pitch: Float): Boolean = playLock.withLock {
        val fmt = ensureGraph(pcm.sampleRate) ?: return@withLock false
        val frames = pcm.samples.size
        val buffer = AVAudioPCMBuffer(pCMFormat = fmt, frameCapacity = frames.convert())
        buffer.frameLength = frames.convert()
        val channel = buffer.floatChannelData?.get(0) ?: return@withLock false
        pcm.samples.usePinned { memcpy(channel, it.addressOf(0), (frames.toLong() * Float.SIZE_BYTES).convert()) }
        // Высота в центах: ×2 = +1200
        timePitch.pitch = CENTS_PER_OCTAVE * log2(pitch.coerceIn(0.5f, 2f))

        val done = CompletableDeferred<Unit>()
        player.scheduleBuffer(buffer) { done.complete(Unit) }
        if (!player.playing) player.play()
        try {
            // Движок может остановиться без колбэка (звонок, смена наушников) — не ждём вечно
            val timeoutMs = frames * 1000L / pcm.sampleRate + EXTRA_WAIT_MS
            withTimeoutOrNull(timeoutMs) { done.await() } != null
        } catch (e: CancellationException) {
            // stop() или следующая фраза: снимаем буфер с очереди, звук обрывается сразу
            player.stop()
            throw e
        }
    }

    override fun release() {
        player.stop()
        engine.stop()
    }

    companion object {
        private const val CENTS_PER_OCTAVE = 1200f
        private const val EXTRA_WAIT_MS = 3_000L
    }
}
