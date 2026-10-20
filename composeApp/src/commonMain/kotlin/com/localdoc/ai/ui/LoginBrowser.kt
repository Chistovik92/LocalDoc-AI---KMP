package com.localdoc.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Управление встроенным браузером входа: адрес текущей страницы и cookie сессии, выданные сайтом
 * после того, как пользователь сам прошёл вход и капчу.
 */
expect class LoginBrowserController() {
    val currentUrl: String

    /** Заголовок `Cookie` (`a=b; c=d`) для текущего сайта. */
    suspend fun cookies(): String
}

/**
 * Системный WebView (Android) / WKWebView (iOS) со страницей входа. Капчу и проверки сайта проходит
 * сам пользователь; приложение их не решает и не обходит, а только забирает итоговую сессию.
 */
@Composable
expect fun LoginBrowser(url: String, controller: LoginBrowserController, modifier: Modifier)

/** Стирает cookie сайта во встроенном браузере (вместе с «Забыть сайт»). */
expect fun clearBrowserCookies(host: String)
