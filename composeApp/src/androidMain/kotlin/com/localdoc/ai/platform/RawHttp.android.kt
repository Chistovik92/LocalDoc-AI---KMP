package com.localdoc.ai.platform

import android.webkit.WebSettings
import com.localdoc.ai.net.WebHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/** Android: HttpURLConnection без автоматических редиректов (их ведёт общий код вместе с cookie). */
actual class RawHttp actual constructor() {

    actual val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(AppContextHolder.appContext) }.getOrNull()
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    actual suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val tls = (connection as? HttpsURLConnection)?.let { TlsGuard.install(it, request) }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = request.timeoutMs
            connection.readTimeout = request.timeoutMs
            connection.requestMethod = request.method
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val headers = HashMap<String, String>()
            val cookies = ArrayList<String>()
            for ((name, values) in connection.headerFields) {
                if (name == null) continue
                if (name.equals("set-cookie", ignoreCase = true)) cookies.addAll(values.orEmpty()) else headers[name.lowercase()] = values.joinToString(", ")
            }
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            RawResponse(status, headers, cookies, stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0))
        } catch (e: SSLException) {
            // Сертификат не прошёл проверку: пользователю предлагается довериться ему (см. UntrustedCertificateException)
            val rejected = tls?.rejected
            if (rejected != null) throw UntrustedCertificateException(WebHttp.hostOf(request.url), rejected.fingerprint, rejected.problem)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun readLimited(stream: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** Отклонённый системой сертификат: отпечаток и причина. */
internal class RejectedCertificate(val fingerprint: String, val problem: CertificateProblem)

/**
 * Проверка сертификата одного HTTPS-соединения: обычная системная проверка; сертификат с отпечатком, которому
 * пользователь доверил сайт, и любой сертификат при включённой галочке принимаются. Отклонённый сертификат запоминается
 * в [rejected], чтобы вызывающий код мог предложить пользователю довериться ему.
 */
internal class TlsGuard private constructor(private val request: RawRequest) {
    @Volatile
    var rejected: RejectedCertificate? = null
        private set

    private val system: X509TrustManager = run {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun allowed(leaf: X509Certificate) = request.trustAnyCertificate || fingerprintOf(leaf) in request.trustedCertificates

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Пустая цепочка сертификатов")
            if (allowed(leaf)) return
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                rejected = RejectedCertificate(fingerprintOf(leaf), problemOf(e, chain))
                throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    /** Имя сайта в сертификате проверяет системная проверка; несовпадение тоже можно разрешить пользователем. */
    private val hostnameVerifier = HostnameVerifier { host, session ->
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
        when {
            leaf == null -> false
            allowed(leaf) -> true
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session) -> true
            else -> {
                rejected = RejectedCertificate(fingerprintOf(leaf), CertificateProblem.HOSTNAME_MISMATCH)
                false
            }
        }
    }

    companion object {
        fun install(connection: HttpsURLConnection, request: RawRequest): TlsGuard {
            val guard = TlsGuard(request)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(guard.trustManager), null)
            connection.sslSocketFactory = context.socketFactory
            connection.hostnameVerifier = guard.hostnameVerifier
            return guard
        }

        fun fingerprintOf(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

        /**
         * Причина отказа: сначала по самому сертификату (срок действия, самоподписанный или цепочка без известного
         * центра), текст исключения системы на разных версиях Android отличается и ненадёжен.
         */
        fun problemOf(error: CertificateException, chain: Array<X509Certificate>): CertificateProblem {
            val leaf = chain.first()
            try {
                leaf.checkValidity()
            } catch (e: CertificateExpiredException) {
                return CertificateProblem.EXPIRED
            } catch (e: CertificateNotYetValidException) {
                return CertificateProblem.NOT_YET_VALID
            }
            if (chain.size == 1 || leaf.issuerX500Principal == leaf.subjectX500Principal) return CertificateProblem.UNKNOWN_ISSUER
            var cause: Throwable? = error
            while (cause != null) {
                if (cause.message.orEmpty().contains("Trust anchor", ignoreCase = true)) return CertificateProblem.UNKNOWN_ISSUER
                cause = cause.cause
            }
            return CertificateProblem.OTHER
        }
    }
}
