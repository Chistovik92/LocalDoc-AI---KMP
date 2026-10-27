package com.localdoc.ai

import androidx.compose.ui.window.ComposeUIViewController
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.IncomingDoc
import com.localdoc.ai.platform.BackgroundUpdates
import com.localdoc.ai.ui.App
import platform.UIKit.UIViewController

/** Вызов из Swift при запуске приложения: регистрирует фоновую задачу проверки новых глав (позже регистрировать нельзя). */
fun registerBackgroundTasks() {
    BackgroundUpdates.register()
}

fun MainViewController(): UIViewController = ComposeUIViewController {
    App()
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
