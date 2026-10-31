package com.localdoc.ai.platform

import android.media.AudioAttributes
import android.media.MediaPlayer

/** Android: MediaPlayer — декодирует MP3/WAV сам, играет из файла приложения. */
actual fun createSampleAudio(): SampleAudio = AndroidSampleAudio()

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
            p.setDataSource(url)
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
