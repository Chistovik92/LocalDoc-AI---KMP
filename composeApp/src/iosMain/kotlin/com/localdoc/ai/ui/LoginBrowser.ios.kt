@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

actual class LoginBrowserController actual constructor() {
    internal var webView: WKWebView? = null
    internal var lastUrl: String = ""

    actual val currentUrl: String get() = webView?.URL?.absoluteString ?: lastUrl

    actual suspend fun cookies(): String {
        val host = webView?.URL?.host ?: currentUrl.substringAfter("://").substringBefore('/')
        if (host.isBlank()) return ""
        return suspendCancellableCoroutine { cont ->
            WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { cookies ->
                val matching = cookies.orEmpty().filterIsInstance<NSHTTPCookie>().filter { c ->
                    val domain = c.domain.removePrefix(".")
                    host == domain || host.endsWith(".$domain")
                }
                cont.resume(matching.joinToString("; ") { "${it.name}=${it.value}" })
            }
        }
    }
}

@Composable
actual fun LoginBrowser(url: String, controller: LoginBrowserController, modifier: Modifier) {
    UIKitView(
        factory = {
            val view = WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration())
            controller.webView = view
            NSURL.URLWithString(url)?.let { view.loadRequest(NSURLRequest.requestWithURL(it)) }
            view
        },
        modifier = modifier,
        onRelease = {
            controller.lastUrl = it.URL?.absoluteString ?: controller.lastUrl
            controller.webView = null
        }
    )
}

actual fun clearBrowserCookies(host: String) {
    val store = WKWebsiteDataStore.defaultDataStore().httpCookieStore
    store.getAllCookies { cookies ->
        cookies.orEmpty().filterIsInstance<NSHTTPCookie>().forEach { c ->
            val domain = c.domain.removePrefix(".")
            if (host == domain || host.endsWith(".$domain") || domain.endsWith(".$host")) store.deleteCookie(c, completionHandler = null)
        }
    }
}
