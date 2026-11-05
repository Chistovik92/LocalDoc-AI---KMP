package com.localdoc.ai.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.localdoc.ai.MainActivity
import com.localdoc.ai.R
import com.localdoc.ai.tts.ReadAloudState

/**
 * Виджет рабочего стола: плеер озвучки (назад / плей-пауза / вперёд / стоп)
 * и быстрые действия «Открыть книгу» и «Ссылка».
 */
class ReaderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, lastState, lastBookTitle)
    }

    companion object {
        private var lastState = ReadAloudState()
        private var lastBookTitle: String? = null

        /** Перерисовка всех экземпляров виджета при смене состояния плеера. */
        fun updateAll(context: Context, state: ReadAloudState, lastBook: String?) {
            lastState = state
            lastBookTitle = lastBook
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReaderWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, state, lastBook)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, state: ReadAloudState, lastBook: String?) {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(
                R.id.widget_title,
                state.title.takeIf { state.active && it.isNotBlank() } ?: lastBook ?: "LocalDoc AI"
            )
            views.setTextViewText(
                R.id.widget_subtitle,
                when {
                    state.active -> state.segment?.text ?: "Пауза"
                    lastBook != null -> "Нажмите ▶, чтобы продолжить чтение вслух"
                    else -> "Откройте книгу или веб-страницу"
                }
            )
            views.setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)

            // Плеер не запущен — «Плей» открывает приложение и продолжает последнюю книгу вслух
            views.setOnClickPendingIntent(
                R.id.widget_play,
                if (state.active) ReadAloudService.servicePendingIntent(context, ReadAloudService.ACTION_TOGGLE) else deepLink(context, "play")
            )
            views.setOnClickPendingIntent(R.id.widget_previous, ReadAloudService.servicePendingIntent(context, ReadAloudService.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_next, ReadAloudService.servicePendingIntent(context, ReadAloudService.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_stop, ReadAloudService.servicePendingIntent(context, ReadAloudService.ACTION_STOP))
            views.setOnClickPendingIntent(R.id.widget_title, deepLink(context, "continue"))
            views.setOnClickPendingIntent(R.id.widget_open_book, deepLink(context, "open-file"))
            views.setOnClickPendingIntent(R.id.widget_open_url, deepLink(context, "open-url"))
            manager.updateAppWidget(ids, views)
        }

        private fun deepLink(context: Context, path: String): PendingIntent = PendingIntent.getActivity(
            context,
            path.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("localdoc://$path"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
