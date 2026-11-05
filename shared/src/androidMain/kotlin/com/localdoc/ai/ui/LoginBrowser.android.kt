package com.localdoc.ai.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

actual class LoginBrowserController actual constructor() {
    internal var webView: WebView? = null
    internal var lastUrl: String = ""

    actual val currentUrl: String get() = webView?.url ?: lastUrl

    actual suspend fun cookies(): String {
        val url = currentUrl.ifBlank { return "" }
        CookieManager.getInstance().flush()
        return CookieManager.getInstance().getCookie(url).orEmpty()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun LoginBrowser(url: String, controller: LoginBrowserController, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // JavaScript нужен самой странице входа и капче; доступ к файлам и мост в приложение отключены
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme.orEmpty()
                        return scheme != "https" && scheme != "http"
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        url?.let { controller.lastUrl = it }
                    }
                }
                controller.webView = this
                loadUrl(url)
            }
        },
        onRelease = { view ->
            controller.lastUrl = view.url ?: controller.lastUrl
            controller.webView = null
            view.destroy()
        }
    )
}

actual fun clearBrowserCookies(host: String) {
    val manager = CookieManager.getInstance()
    val names = listOf("https://$host", "http://$host").flatMap { url ->
        manager.getCookie(url).orEmpty().split(';').mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
    }.distinct()
    for (name in names) {
        for (domain in listOf(host, ".$host")) {
            manager.setCookie("https://$host", "$name=; Max-Age=0; Path=/; Domain=$domain")
        }
        manager.setCookie("https://$host", "$name=; Max-Age=0; Path=/")
    }
    manager.flush()
}
