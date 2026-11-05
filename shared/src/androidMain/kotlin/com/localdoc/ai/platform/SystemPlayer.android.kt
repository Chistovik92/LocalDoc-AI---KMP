package com.localdoc.ai.platform

import com.localdoc.ai.domain.AppGraph
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
                        runCatching { AndroidHost.startPlayerService(context) }
                    }
                    wasLive = live
                    AndroidHost.updatePlayerWidget(context, state, lastBook)
                }
        }
    }
}
