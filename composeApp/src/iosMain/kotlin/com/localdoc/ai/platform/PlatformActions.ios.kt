package com.localdoc.ai.platform

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
actual object PlatformActions {

    actual val appVersion: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"

    actual val supportsTtsEngines: Boolean = false

    actual fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    /** На iOS голоса загружаются в системных настройках — открываем настройки приложения. */
    actual fun openTtsSettings() = openUrl(UIApplicationOpenSettingsURLString)

    actual fun installTtsData() = openTtsSettings()

    actual suspend fun httpGet(url: String, headers: Map<String, String>): String =
        suspendCancellableCoroutine { cont ->
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                cont.resumeWithException(IllegalArgumentException("Некорректный адрес: $url"))
                return@suspendCancellableCoroutine
            }
            val request = NSMutableURLRequest.requestWithURL(nsUrl)
            headers.forEach { (k, v) -> request.setValue(v, forHTTPHeaderField = k) }
            val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
                val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                when {
                    error != null -> cont.resumeWithException(IllegalStateException(error.localizedDescription))
                    code !in 200..299 -> cont.resumeWithException(IllegalStateException("HTTP $code"))
                    else -> {
                        val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
                        cont.resume(text)
                    }
                }
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
