package com.localdoc.ai

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.localdoc.ai.platform.AndroidHost
import com.localdoc.ai.platform.AppContextHolder
import com.localdoc.ai.player.ReadAloudService
import com.localdoc.ai.player.ReaderWidgetProvider

/**
 * Контекст приложения доступен до Activity: виджет и сервис плеера могут
 * обратиться к AppGraph, когда процесс поднят системой без открытия экрана.
 * Здесь же заполняется мост [AndroidHost] — shared не может ссылаться на
 * классы приложения напрямую (направление зависимости shared ← androidApp).
 */
class LocalDocApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)

        AndroidHost.startPlayerService = { context ->
            context.startForegroundService(Intent(context, ReadAloudService::class.java))
        }
        AndroidHost.updatePlayerWidget = { context, state, lastBook ->
            ReaderWidgetProvider.updateAll(context, state, lastBook)
        }
        AndroidHost.openAppPendingIntent = { context ->
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("localdoc://continue")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        AndroidHost.notificationSmallIconRes = R.drawable.ic_stat_reader
    }
}
