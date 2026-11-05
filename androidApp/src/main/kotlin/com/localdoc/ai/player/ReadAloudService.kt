package com.localdoc.ai.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.localdoc.ai.MainActivity
import com.localdoc.ai.R
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.tts.ReadAloudState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Плеер в шторке: foreground-сервис с уведомлением MediaStyle и MediaSession.
 * Кнопки уведомления, гарнитуры и экрана блокировки управляют ReadAloudController,
 * а сам сервис не даёт системе остановить чтение, когда приложение свёрнуто.
 */
class ReadAloudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        session = MediaSession(this, "LocalDocReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = AppGraph.readAloud.play()
                override fun onPause() = AppGraph.readAloud.pause()
                override fun onSkipToNext() = AppGraph.readAloud.nextSentence()
                override fun onSkipToPrevious() = AppGraph.readAloud.previousSentence()
                override fun onFastForward() = AppGraph.readAloud.nextParagraph()
                override fun onRewind() = AppGraph.readAloud.previousParagraph()
                override fun onStop() = AppGraph.readAloud.stop()
            })
            isActive = true
        }
        // Тип сервиса передаётся только с Android 10 (API 29); на Android 8–9 ServiceCompat вызывает вариант без типа
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(AppGraph.readAloud.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        scope.launch {
            AppGraph.readAloud.state.collect { state ->
                // Таймер сна остановил озвучку: сервис и уведомление не нужны, позиция остаётся в приложении
                if (!state.active || state.sleepStopped) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateSession(state)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = AppGraph.readAloud
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_NEXT -> player.nextSentence()
            ACTION_PREVIOUS -> player.previousSentence()
            ACTION_STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun updateSession(state: ReadAloudState) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifBlank { "LocalDoc AI" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.segment?.text?.take(120).orEmpty())
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
                )
                .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, state.position.toLong(), 1f)
                .build()
        )
    }

    private fun buildNotification(state: ReadAloudState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("localdoc://continue")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(icon: Int, title: String, intentAction: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), title, servicePendingIntent(this, intentAction)).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle(state.title.ifBlank { "LocalDoc AI — озвучка" })
            .setContentText(state.segment?.text ?: "Пауза")
            .setContentIntent(open)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(R.drawable.ic_player_previous, "Назад", ACTION_PREVIOUS))
            .addAction(
                if (state.playing) action(R.drawable.ic_player_pause, "Пауза", ACTION_TOGGLE)
                else action(R.drawable.ic_player_play, "Читать", ACTION_TOGGLE)
            )
            .addAction(action(R.drawable.ic_player_next, "Вперёд", ACTION_NEXT))
            .addAction(action(R.drawable.ic_player_stop, "Стоп", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        const val ACTION_TOGGLE = "com.localdoc.ai.player.TOGGLE"
        const val ACTION_NEXT = "com.localdoc.ai.player.NEXT"
        const val ACTION_PREVIOUS = "com.localdoc.ai.player.PREVIOUS"
        const val ACTION_STOP = "com.localdoc.ai.player.STOP"
        private const val CHANNEL_ID = "read_aloud"
        private const val NOTIFICATION_ID = 1501

        fun servicePendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, ReadAloudService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Озвучка книг", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Плеер чтения вслух в шторке уведомлений"
                    setShowBadge(false)
                }
            )
        }
    }
}
