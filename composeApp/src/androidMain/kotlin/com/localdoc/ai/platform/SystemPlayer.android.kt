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
            var wasActive = false
            combine(AppGraph.readAloud.state, AppGraph.library.entries) { state, entries -> state to entries.firstOrNull()?.title }
                .collect { (state, lastBook) ->
                    // Сервис запускается из приложения на переднем плане (нажатие «Слушать»)
                    if (state.active && !wasActive) {
                        runCatching { context.startForegroundService(Intent(context, ReadAloudService::class.java)) }
                    }
                    wasActive = state.active
                    ReaderWidgetProvider.updateAll(context, state, lastBook)
                }
        }
    }
}
