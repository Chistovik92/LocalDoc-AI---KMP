package com.localdoc.ai.platform

import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Android: HttpURLConnection без автоматических редиректов (их ведёт общий код вместе с cookie). */
actual class RawHttp actual constructor() {

    actual val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(AppContextHolder.appContext) }.getOrNull()
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    actual suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
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
