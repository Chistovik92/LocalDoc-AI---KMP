package com.localdoc.ai.platform

import android.app.PendingIntent
import android.content.Context
import com.localdoc.ai.tts.ReadAloudState

/**
 * Мост из `shared` к классам Android-приложения (`MainActivity`, `ReadAloudService`,
 * `ReaderWidgetProvider`, ресурсы `R`), которые живут в модуле `androidApp`.
 *
 * Направление зависимости: `shared` ← `androidApp`. Поэтому `shared` не может ссылаться
 * на эти классы напрямую — androidApp заполняет лямбды при старте (в `LocalDocApp.onCreate`).
 * Лямбды тривиальны и ничего не хранят: нужный `Context` передаётся аргументом.
 */
object AndroidHost {

    /** Запуск foreground-сервиса плеера (ReadAloudService). */
    var startPlayerService: (Context) -> Unit = { }

    /** Перерисовка виджета плеера (ReaderWidgetProvider.updateAll). */
    var updatePlayerWidget: (Context, ReadAloudState, String?) -> Unit = { _, _, _ -> }

    /** PendingIntent, открывающий MainActivity по deep-link `localdoc://continue`. */
    var openAppPendingIntent: (Context) -> PendingIntent = {
        throw IllegalStateException("AndroidHost не инициализирован")
    }

    /** Ресурс маленькой иконки уведомления о новых главах (R.drawable.ic_stat_reader). */
    var notificationSmallIconRes: Int = 0
}
