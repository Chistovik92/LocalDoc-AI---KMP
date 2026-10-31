package com.localdoc.ai.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile

/** iOS: AVAudioPlayer — декодирует MP3/WAV сам, играет из файла приложения. */
@OptIn(ExperimentalForeignApi::class)
actual fun createSampleAudio(): SampleAudio = IosSampleAudio()

@OptIn(ExperimentalForeignApi::class)
private class IosSampleAudio : SampleAudio {

    private var player: AVAudioPlayer? = null

    override fun play(url: String): Boolean {
        stop()
        val data: NSData = NSData.create(contentsOfFile = url) ?: return false
        return runCatching {
            val p = AVAudioPlayer(data = data, error = null)
            p.prepareToPlay()
            p.play()
            player = p
            true
        }.getOrDefault(false)
    }

    override fun stop() {
        player?.let { runCatching { it.stop() } }
        player = null
    }

    override fun release() = stop()
}
