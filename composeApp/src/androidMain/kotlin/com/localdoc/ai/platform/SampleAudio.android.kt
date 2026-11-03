package com.localdoc.ai.platform

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Android: MediaPlayer — декодирует MP3/WAV сам, играет из файла приложения. */
actual fun createSampleAudio(): SampleAudio = AndroidSampleAudio()

actual fun isBundledSample(url: String): Boolean =
    url.startsWith("https://raw.githubusercontent.com/Chistovik92/LocalDoc-AI---KMP/main/voice-samples/")

private class AndroidSampleAudio : SampleAudio {

    private var player: MediaPlayer? = null

    override fun play(url: String): Boolean {
        stop()
        val p = MediaPlayer()
        val started = runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            if (url.startsWith("https://raw.githubusercontent.com/Chistovik92/LocalDoc-AI---KMP/main/voice-samples/")) {
                val name = url.substringAfterLast('/')
                val afd = AppContextHolder.appContext.assets.openFd("voice-samples/$name")
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            } else {
                p.setDataSource(url)
            }
            p.setOnCompletionListener { it.release() }
            p.prepare()
            p.start()
            true
        }.getOrElse {
            p.release()
            false
        }
        if (started) player = p
        return started
    }

    override fun stop() {
        player?.let { p ->
            runCatching {
                if (p.isPlaying) p.stop()
                p.release()
            }
        }
        player = null
    }

    override fun release() = stop()
}
