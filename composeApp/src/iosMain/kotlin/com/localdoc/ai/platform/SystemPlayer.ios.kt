package com.localdoc.ai.platform

import com.localdoc.ai.domain.AppGraph
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSNumber
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

/**
 * iOS: плеер озвучки в «Пункте управления», на экране блокировки и в наушниках —
 * MPNowPlayingInfoCenter + MPRemoteCommandCenter. Категория Playback позволяет
 * слушать книгу в фоне и при беззвучном режиме.
 */
@OptIn(ExperimentalForeignApi::class)
actual object SystemPlayer {
    private var started = false

    actual fun start() {
        if (started) return
        started = true

        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)

        val player = AppGraph.readAloud
        val commands = MPRemoteCommandCenter.sharedCommandCenter()
        val ok = MPRemoteCommandHandlerStatusSuccess
        listOf(
            commands.playCommand, commands.pauseCommand, commands.togglePlayPauseCommand,
            commands.nextTrackCommand, commands.previousTrackCommand, commands.stopCommand
        ).forEach { it.enabled = true }
        commands.playCommand.addTargetWithHandler { player.play(); ok }
        commands.pauseCommand.addTargetWithHandler { player.pause(); ok }
        commands.togglePlayPauseCommand.addTargetWithHandler { player.toggle(); ok }
        commands.nextTrackCommand.addTargetWithHandler { player.nextSentence(); ok }
        commands.previousTrackCommand.addTargetWithHandler { player.previousSentence(); ok }
        commands.stopCommand.addTargetWithHandler { player.stop(); ok }

        AppGraph.appScope.launch {
            var wasActive = false
            player.state.collect { state ->
                val center = MPNowPlayingInfoCenter.defaultCenter()
                if (!state.active) {
                    center.nowPlayingInfo = null
                    if (wasActive) session.setActive(false, error = null)
                } else {
                    if (!wasActive) session.setActive(true, error = null)
                    center.nowPlayingInfo = mapOf<Any?, Any?>(
                        MPMediaItemPropertyTitle to state.title.ifBlank { "LocalDoc AI" },
                        MPMediaItemPropertyArtist to (state.segment?.text?.take(120) ?: ""),
                        MPNowPlayingInfoPropertyPlaybackRate to NSNumber(double = if (state.playing) 1.0 else 0.0),
                        // Условная длительность в фрагментах озвучки — без неё карточка может пропадать
                        MPMediaItemPropertyPlaybackDuration to NSNumber(double = state.total.toDouble()),
                        MPNowPlayingInfoPropertyElapsedPlaybackTime to NSNumber(double = state.position.toDouble())
                    )
                }
                wasActive = state.active
            }
        }
    }
}
