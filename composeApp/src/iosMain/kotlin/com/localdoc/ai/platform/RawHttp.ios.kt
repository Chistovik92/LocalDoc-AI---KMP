@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.localdoc.ai.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.appendData
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS: NSURLSession без редиректов и без системных cookie; тело читается потоком и обрывается, как только
 * превышен [maxBytes] (память не раздувается); Set-Cookie собираются из разобранных системой cookie.
 */
actual class RawHttp actual constructor() {

    actual val userAgent: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    actual suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse =
        suspendCancellableCoroutine { cont ->
            val nsUrl = NSURL.URLWithString(request.url)
            if (nsUrl == null) {
                cont.resumeWithException(IllegalArgumentException("Некорректный адрес: ${request.url}"))
                return@suspendCancellableCoroutine
            }
            val req = NSMutableURLRequest(uRL = nsUrl)
            req.HTTPMethod = request.method
            req.setTimeoutInterval(request.timeoutMs / 1000.0)
            req.HTTPShouldHandleCookies = false
            request.headers.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
            request.body?.let { req.HTTPBody = it.toNSData() }

            val config = NSURLSessionConfiguration.ephemeralSessionConfiguration()
            config.HTTPShouldSetCookies = false

            var sessionRef: NSURLSession? = null
            var finished = false
            val delegate = CollectingDelegate(maxBytes) { http, body, tooLarge, error ->
                sessionRef?.finishTasksAndInvalidate()
                if (finished) return@CollectingDelegate
                finished = true
                when {
                    tooLarge -> cont.resumeWithException(ResponseTooLargeException(maxBytes))
                    http == null -> cont.resumeWithException(IllegalStateException(error ?: "Нет ответа от сервера"))
                    else -> {
                        val fields = http.allHeaderFields
                        val headers = HashMap<String, String>()
                        for ((k, v) in fields) {
                            val name = (k as? String)?.lowercase() ?: continue
                            if (name != "set-cookie") headers[name] = v.toString()
                        }
                        val cookies = NSHTTPCookie.cookiesWithResponseHeaderFields(fields, forURL = nsUrl)
                            .filterIsInstance<NSHTTPCookie>()
                            .map(::setCookieLine)
                        cont.resume(RawResponse(http.statusCode.toInt(), headers, cookies, body.toByteArray()))
                    }
                }
            }
            val session = NSURLSession.sessionWithConfiguration(config, delegate = delegate, delegateQueue = null)
            sessionRef = session
            val task = session.dataTaskWithRequest(req)
            cont.invokeOnCancellation { task.cancel(); session.invalidateAndCancel() }
            task.resume()
        }

    private fun setCookieLine(cookie: NSHTTPCookie): String {
        val sb = StringBuilder("${cookie.name}=${cookie.value}")
        // Домен с ведущей точкой — cookie для всего домена; без неё — только для этого хоста
        if (cookie.domain.startsWith(".")) sb.append("; Domain=").append(cookie.domain.removePrefix("."))
        sb.append("; Path=").append(cookie.path)
        cookie.expiresDate?.let { sb.append("; Max-Age=").append(it.timeIntervalSinceNow.toLong().coerceAtLeast(0)) }
        if (cookie.secure) sb.append("; Secure")
        return sb.toString()
    }
}

/**
 * Делегат сессии: редиректы не выполняются (ответ 30x отдаётся вызывающему коду), тело накапливается потоком
 * и обрывается при превышении лимита — как по заголовку Content-Length, так и по фактическому объёму.
 */
private class CollectingDelegate(
    private val maxBytes: Long,
    private val onFinish: (NSHTTPURLResponse?, NSData, Boolean, String?) -> Unit
) : NSObject(), NSURLSessionDataDelegateProtocol {

    private var response: NSHTTPURLResponse? = null
    private val buffer = NSMutableData()
    private var tooLarge = false

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit
    ) {
        completionHandler(null)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit
    ) {
        response = didReceiveResponse as? NSHTTPURLResponse
        if (didReceiveResponse.expectedContentLength > maxBytes) {
            tooLarge = true
            completionHandler(NSURLSessionResponseCancel)
        } else {
            completionHandler(NSURLSessionResponseAllow)
        }
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (tooLarge) return
        if (buffer.length.toLong() + didReceiveData.length.toLong() > maxBytes) {
            tooLarge = true
            dataTask.cancel()
            return
        }
        buffer.appendData(didReceiveData)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        onFinish(response, buffer, tooLarge, didCompleteWithError?.localizedDescription)
    }
}
