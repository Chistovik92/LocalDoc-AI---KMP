package com.localdoc.ai

import androidx.compose.ui.window.ComposeUIViewController
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.IncomingDoc
import com.localdoc.ai.platform.BackgroundUpdates
import com.localdoc.ai.ui.App
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLog
import platform.UIKit.UIViewController

/** Вызов из Swift при запуске приложения: регистрирует фоновую задачу проверки новых глав (позже регистрировать нельзя). */
fun registerBackgroundTasks() {
    BackgroundUpdates.register()
}

@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController {
    // ВРЕМЕННАЯ диагностика сбоя первой композиции на iOS: исключение печатается в системный
    // лог (видно через `log show --predicate 'process == "LocalDocAI"'`), иначе остаётся только
    // стек в .ips без самого сообщения. Убрать после локализации и фикса причины.
    try {
        App()
    } catch (t: Throwable) {
        NSLog("LocalDoc UNCAUGHT App() composition: %@", t.toString())
        NSLog("LocalDoc UNCAUGHT stacktrace:\n%@", t.stackTraceToString())
        throw t
    }
}

/**
 * Вызов из SwiftUI .onOpenURL: ссылки виджета (localdoc://continue | play | open-file | open-url),
 * файлы книг из «Файлов» / «Поделиться» и веб-ссылки.
 */
fun handleOpenUrl(url: String) {
    when {
        url.startsWith("localdoc://") -> AppGraph.handleDeepLink(url)
        // Для файлов Swift передаёт уже декодированный путь (URL.path)
        url.startsWith("/") -> AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.FILE, url))
        url.startsWith("http://") || url.startsWith("https://") ->
            AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.URL, url))
    }
}

/**
 * Вызов из App Intents виджета (iOS 17+): команды плеера озвучки — toggle | next | previous.
 * Если плеер не открыт, «toggle» запускает чтение так же, как кнопка «Слушать» виджета.
 */
fun playerCommand(name: String) {
    val player = AppGraph.readAloud
    when (name) {
        "toggle" -> if (player.state.value.active) player.toggle() else AppGraph.handleDeepLink("localdoc://play")
        "next" -> player.nextSentence()
        "previous" -> player.previousSentence()
    }
}
