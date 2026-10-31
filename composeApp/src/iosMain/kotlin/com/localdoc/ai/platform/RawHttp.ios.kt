@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.localdoc.ai.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import com.localdoc.ai.net.NetSafety
import com.localdoc.ai.net.WebHttp
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustRef
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

    actual suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse {
        NetSafety.checkCleartext(request.url)
        // DNS-перепривязка: проверяем IP, к которому реально идёт соединение, а не только имя
        val host = WebHttp.hostOf(request.url)
        if (!NetSafety.isInternalHost(host)) {
            val resolved = runCatching { resolveHost(host) }.getOrDefault(emptyList())
            NetSafety.checkResolved(host, resolved)
        }
        return executeRequest(request, maxBytes)
    }

    private suspend fun executeRequest(request: RawRequest, maxBytes: Long): RawResponse =
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
            val delegate = CollectingDelegate(maxBytes, request.trustedCertificates, request.trustAnyCertificate) { http, body, tooLarge, error, untrusted ->
                sessionRef?.finishTasksAndInvalidate()
                if (finished) return@CollectingDelegate
                finished = true
                when {
                    tooLarge -> cont.resumeWithException(ResponseTooLargeException(maxBytes))
                    // Сертификат не прошёл проверку: пользователю предлагается довериться ему
                    untrusted != null -> cont.resumeWithException(
                        UntrustedCertificateException(WebHttp.hostOf(request.url), untrusted, CertificateProblem.OTHER)
                    )
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
    /** Отпечатки SHA-256 сертификатов, которым пользователь доверил этот сайт. */
    private val trustedCertificates: Set<String>,
    /** Галочка «открывать сайты с любым сертификатом». */
    private val trustAnyCertificate: Boolean,
    private val onFinish: (NSHTTPURLResponse?, NSData, Boolean, String?, String?) -> Unit
) : NSObject(), NSURLSessionDataDelegateProtocol {

    private var response: NSHTTPURLResponse? = null
    private val buffer = NSMutableData()
    private var tooLarge = false

    /** Отпечаток сертификата, отклонённого системой и не одобренного пользователем. */
    private var untrustedFingerprint: String? = null

    /**
     * Проверка сертификата сайта: системная; при отказе принимается сертификат, которому пользователь доверил сайт
     * (по отпечатку), либо любой — при включённой галочке. Иначе соединение обрывается, а отпечаток уходит в [onFinish].
     */
    override fun URLSession(
        session: NSURLSession,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit
    ) {
        val space = didReceiveChallenge.protectionSpace
        val trust = space.serverTrust
        if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        if (SecTrustEvaluateWithError(trust, null)) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val fingerprint = leafFingerprint(trust)
        if (trustAnyCertificate || (fingerprint != null && fingerprint in trustedCertificates)) {
            completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
            return
        }
        untrustedFingerprint = fingerprint
        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
    }

    private fun leafFingerprint(trust: SecTrustRef): String? {
        val certificate = SecTrustGetCertificateAtIndex(trust, 0) ?: return null
        val data = SecCertificateCopyData(certificate) ?: return null
        val bytes = (CFBridgingRelease(data) as? NSData)?.toByteArray() ?: return null
        return sha256Hex(bytes)
    }

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
        onFinish(response, buffer, tooLarge, didCompleteWithError?.localizedDescription, untrustedFingerprint)
    }
}

/** Адреса, на которые резолвится имя хоста (IPv4 и IPv6), — getaddrinfo/getnameinfo без ObjC-мостов. */
@OptIn(ExperimentalForeignApi::class)
private fun resolveHost(host: String): List<String> = memScoped {
    val hints = alloc<platform.posix.addrinfo>()
    platform.posix.memset(hints.ptr, 0, sizeOf<platform.posix.addrinfo>().convert())
    val result = allocPointerTo<platform.posix.addrinfo>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped emptyList()
    val head = result.value ?: return@memScoped emptyList()
    val addresses = ArrayList<String>()
    try {
        // Буфер под текстовую форму адреса: 46 байт хватает для IPv6 с зоной
        val text = allocArray<ByteVar>(46)
        var current: platform.posix.addrinfo? = head.pointed
        while (current != null) {
            val addr = current.ai_addr
            if (addr != null) {
                platform.posix.memset(text, 0, 46.convert())
                if (getnameinfo(addr, current.ai_addrlen.convert(), text, 46.convert(), null, 0.convert(), platform.posix.NI_NUMERICHOST) == 0) {
                    text.toKString().let { if (it.isNotBlank()) addresses.add(it) }
                }
            }
            current = current.ai_next?.reinterpret<platform.posix.addrinfo>()?.pointed
        }
    } finally {
        freeaddrinfo(head)
    }
    addresses
}
