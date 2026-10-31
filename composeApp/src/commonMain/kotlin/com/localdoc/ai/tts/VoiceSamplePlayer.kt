package com.localdoc.ai.tts

import com.localdoc.ai.platform.FileAccess
import com.localdoc.ai.platform.SampleAudio
import com.localdoc.ai.platform.createSampleAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Проигрывает короткий MP3-сэмпл голоса без установки полного пакета: файл скачивается
 * (или берётся из дискового кэша) и играется платформенным декодером [SampleAudio]
 * (Android MediaPlayer, iOS AVAudioPlayer — MP3 и WAV декодируют сами).
 * Скачанный сэмпл сохраняется на диск, чтобы не качать его заново при следующем запуске.
 *
 * Повторные нажатия ▶ не запускают несколько копий: загрузка сэмпла одна на все запросы,
 * а играет всегда один плеер — прежняя реплика останавливается перед следующей.
 */
class VoiceSamplePlayer(
    private val fetch: suspend (url: String) -> ByteArray?,
    private val files: FileAccess,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.Default
) {
    private val audio = createSampleAudio()

    /** Одна загрузка на адрес: повторные нажатия ждут её, а не качают сэмпл заново. */
    private val loading = HashMap<String, Deferred<ByteArray?>>()
    private val loadingMutex = Mutex()

    /** Запись файла и запуск плеера идут по одному: сэмплы не играют поверх друг друга. */
    private val playMutex = Mutex()

    /** true — воспроизведение началось; false — сэмпла нет или скачать не удалось. */
    fun play(url: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching { playOnce(url) }.getOrDefault(false)
            onResult(ok)
        }
    }

    private suspend fun playOnce(url: String): Boolean {
        val bytes = cachedBytes(url) ?: return false
        return playMutex.withLock {
            withContext(io) {
                // Запись во временный файл приложения: платформенные плееры играют из файла, а не из памяти
                files.writeAppFile(SAMPLE_TMP, bytes)
                audio.play(files.appFilePath(SAMPLE_TMP))
            }
        }
    }

    /** Байты сэмпла: из кэша на диске или одна общая загрузка на все одновременные запросы. */
    private suspend fun cachedBytes(url: String): ByteArray? {
        val name = SAMPLE_PREFIX + url.hashCode().toString(16) + ".mp3"
        files.readAppFile(name)?.let { return it }
        val job = loadingMutex.withLock {
            loading[url] ?: scope.async {
                val bytes = runCatching { fetch(url) }.getOrNull()
                if (bytes != null) withContext(io) { files.writeAppFile(name, bytes) }
                bytes
            }.also { created ->
                loading[url] = created
                created.invokeOnCompletion {
                    scope.launch { loadingMutex.withLock { if (loading[url] === created) loading.remove(url) } }
                }
            }
        }
        return runCatching { job.await() }.getOrNull()
    }

    fun stop() = audio.stop()

    fun release() = audio.release()

    private companion object {
        const val SAMPLE_PREFIX = "voice_sample_"
        const val SAMPLE_TMP = "voice_sample_current.mp3"
    }
}
