package com.localdoc.ai.tts

import com.localdoc.ai.platform.FileAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Проигрывает короткий WAV-сэмпл голоса без установки полного пакета: файл скачивается
 * (или берётся из дискового кэша), декодируется в PCM и играет через общий вывод [PcmOutput].
 * Скачанный сэмпл сохраняется на диск, чтобы не качать его заново при следующем запуске.
 */
class VoiceSamplePlayer(
    private val fetch: suspend (url: String) -> ByteArray?,
    private val files: FileAccess,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.Default
) {
    private val cache = HashMap<String, Pcm>()
    private val output = createPcmOutput()

    /** null — сэмпла нет или скачать не удалось (сообщение об ошибке возвращается в [onResult]). */
    fun play(url: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { playBlocking(url) }.getOrDefault(false)
            onResult(ok)
        }
    }

    private suspend fun playBlocking(url: String): Boolean {
        val pcm = withContext(io) {
            cache[url] ?: bytesFor(url)?.let(::decode)?.also { cache[url] = it }
        } ?: return false
        return output.play(pcm, pitch = 1f)
    }

    /** Байты WAV: из дискового кэша или по сети (результат сохраняется в кэш). */
    private suspend fun bytesFor(url: String): ByteArray? {
        val name = SAMPLE_PREFIX + url.hashCode().toString(16) + ".wav"
        files.readAppFile(name)?.let { return it }
        val downloaded = fetch(url) ?: return null
        files.writeAppFile(name, downloaded)
        return downloaded
    }

    private fun decode(bytes: ByteArray): Pcm? = WavDecoder.decode(bytes)

    fun stop() = output.release()

    private companion object {
        const val SAMPLE_PREFIX = "voice_sample_"
    }
}

/**
 * Распаковка PCM-сэмпла (16-бит или 32-бит float, 1–2 канала) из WAV (RIFF).
 * Нужна только для коротких сэмплов голоса; файлы книг так не читаются.
 */
object WavDecoder {

    fun decode(bytes: ByteArray): Pcm? {
        if (bytes.size < 44) return null
        val view = Reader(bytes)
        if (view.tag() != "RIFF") return null
        view.skip(4) // размер RIFF
        if (view.tag() != "WAVE") return null

        var channels = 1
        var sampleRate = 22_050
        var bits = 16
        var dataOffset = -1
        var dataLength = -1

        while (view.has(8)) {
            val chunk = view.tag()
            val size = view.i32()
            when (chunk) {
                "fmt " -> {
                    val format = view.u16()
                    if (format != 1 && format != 3) return null // только PCM / IEEE float
                    channels = view.u16()
                    sampleRate = view.i32()
                    view.skip(6)
                    bits = view.u16()
                    view.skip(size - 16)
                }
                "data" -> {
                    dataOffset = view.pos
                    dataLength = size
                    view.skip(size + (size and 1))
                }
                else -> view.skip(size + (size and 1))
            }
        }
        if (dataOffset < 0 || dataLength <= 0) return null

        val bytesPerSample = bits / 8
        val frameSize = channels * bytesPerSample
        if (frameSize <= 0) return null
        val frameCount = dataLength / frameSize
        if (frameCount <= 0) return null

        val samples = FloatArray(frameCount)
        var i = 0
        var j = 0
        val base = dataOffset
        val isFloat = bits == 32
        while (i < frameCount) {
            var v = 0f
            for (c in 0 until channels) {
                val off = base + j + c * bytesPerSample
                v += if (isFloat) {
                    bytes.f32(off)
                } else {
                    bytes.i16(off) / 32768f
                }
            }
            samples[i] = (v / channels).coerceIn(-1f, 1f)
            i++
            j += frameSize
        }
        return Pcm(samples, sampleRate)
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun has(n: Int) = pos + n <= b.size
        fun tag(): String = String(b, pos, 4)
        fun skip(n: Int) { pos += n }
        fun i32(): Int { val v = b.le32(pos); pos += 4; return v }
        fun u16(): Int { val v = b.le16(pos); pos += 2; return v }
    }

    private fun ByteArray.i16(off: Int): Int {
        val v = (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun ByteArray.f32(off: Int): Float =
        Float.fromBits(le32(off))

    private fun ByteArray.le32(off: Int): Int =
        (this[off].toInt() and 0xFF) or
            ((this[off + 1].toInt() and 0xFF) shl 8) or
            ((this[off + 2].toInt() and 0xFF) shl 16) or
            ((this[off + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.le16(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)
}
