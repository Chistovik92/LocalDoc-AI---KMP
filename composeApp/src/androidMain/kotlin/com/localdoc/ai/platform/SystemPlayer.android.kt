package com.localdoc.ai.platform

import android.content.Intent
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.player.ReadAloudService
import com.localdoc.ai.player.ReaderWidgetProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

actual object SystemPlayer {
    private var started = false

    actual fun start() {
        if (started) return
        started = true
        val context = AppContextHolder.appContext
        AppGraph.appScope.launch {
            var wasLive = false
            combine(AppGraph.readAloud.state, AppGraph.library.entries) { state, entries -> state to entries.firstOrNull()?.title }
                .collect { (state, lastBook) ->
                    // Сервис запускается из приложения на переднем плане (нажатие «Слушать»)
                    // После остановки таймером сна сервис не работает: запускаем снова, когда чтение возобновилось
                    val live = state.active && !state.sleepStopped
                    if (live && !wasLive) {
                        runCatching { context.startForegroundService(Intent(context, ReadAloudService::class.java)) }
                    }
                    wasLive = live
                    ReaderWidgetProvider.updateAll(context, state, lastBook)
                }
        }
    }
}
