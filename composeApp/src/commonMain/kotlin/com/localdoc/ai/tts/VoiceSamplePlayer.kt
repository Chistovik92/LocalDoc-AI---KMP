package com.localdoc.ai.tts

import com.localdoc.ai.platform.FileAccess
import com.localdoc.ai.platform.SampleAudio
import com.localdoc.ai.platform.createSampleAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Проигрывает короткий MP3-сэмпл голоса без установки полного пакета: файл скачивается
 * (или берётся из дискового кэша) и играется платформенным декодером [SampleAudio]
 * (Android MediaPlayer, iOS AVAudioPlayer — MP3 и WAV декодируют сами).
 * Скачанный сэмпл сохраняется на диск, чтобы не качать его заново при следующем запуске.
 */
class VoiceSamplePlayer(
    private val fetch: suspend (url: String) -> ByteArray?,
    private val files: FileAccess,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.Default
) {
    private val audio = createSampleAudio()

    /** true — воспроизведение началось; false — сэмпла нет или скачать не удалось. */
    fun play(url: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching { playBlocking(url) }.getOrDefault(false)
            onResult(ok)
        }
    }

    private suspend fun playBlocking(url: String): Boolean {
        val name = SAMPLE_PREFIX + url.hashCode().toString(16) + ".mp3"
        val cached = files.readAppFile(name) ?: fetch(url)?.also { files.writeAppFile(name, it) }
            ?: return false
        return withContext(io) {
            // Запись во временный файл приложения: платформенные плееры играют из файла, а не из памяти
            files.writeAppFile(SAMPLE_TMP, cached)
            audio.play(files.appFilePath(SAMPLE_TMP))
        }
    }

    fun stop() = audio.stop()

    fun release() = audio.release()

    private companion object {
        const val SAMPLE_PREFIX = "voice_sample_"
        const val SAMPLE_TMP = "voice_sample_current.mp3"
    }
}
