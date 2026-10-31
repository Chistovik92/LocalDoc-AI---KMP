package com.localdoc.ai.net

import com.localdoc.ai.book.TextDecoder
import com.localdoc.ai.book.WebNavigationDetector
import com.localdoc.ai.platform.RawHttp
import com.localdoc.ai.platform.RawRequest
import com.localdoc.ai.platform.RawResponse
import com.localdoc.ai.platform.SecureStore

/** Транспорт запросов: платформенный [RawHttp] в приложении, подделка в тестах. */
interface HttpTransport {
    val userAgent: String
    suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse
}

fun RawHttp.asTransport(): HttpTransport = object : HttpTransport {
    override val userAgent: String get() = this@asTransport.userAgent
    override suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse = this@asTransport.execute(request, maxBytes)
}

fun SecureStore.asStorage(): SecretStorage = object : SecretStorage {
    override fun get(key: String) = this@asStorage.get(key)
    override fun put(key: String, value: String) = this@asStorage.put(key, value)
    override fun remove(key: String) = this@asStorage.remove(key)
}

/**
 * Что разрешено пользователем для сайта с непроверенным сертификатом: [fingerprints] — отпечатки SHA-256 сертификатов,
 * которым он доверил этот сайт, [any] — галочка «открывать сайты с любым сертификатом».
 */
class CertificateAllowance(val fingerprints: Set<String>, val any: Boolean) {
    companion object {
        val NONE = CertificateAllowance(emptySet(), false)
    }
}

/** Итог запроса после всех редиректов. [url] — адрес последнего ответа. */
class HttpResult(val url: String, val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val contentType: String get() = headers["content-type"].orEmpty().lowercase()
    val isOk: Boolean get() = status in 200..299

    /** Текст ответа в кодировке из заголовка / meta (UTF-8 и Windows-1251 определяются и без неё). */
    fun text(): String {
        val declared = CHARSET.find(contentType)?.groupValues?.get(1)
            ?: CHARSET.find(body.copyOf(minOf(body.size, 2048)).decodeToString())?.groupValues?.get(1)
        return TextDecoder.decode(body, declared)
    }

    private companion object {
        val CHARSET = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
    }
}

class HttpStatusException(val status: Int, val url: String) : IllegalStateException(messageFor(status)) {
    private companion object {
        fun messageFor(status: Int) = when (status) {
            404, 410 -> "Страница не найдена (HTTP $status)"
            429 -> "Сайт просит подождать: слишком много запросов (HTTP 429)"
            in 500..599 -> "Сайт временно недоступен (HTTP $status)"
            else -> "Сервер ответил ошибкой HTTP $status"
        }
    }
}

/**
 * Общий HTTP-клиент приложения: ведёт редиректы вручную (до [MAX_REDIRECTS]), подставляет cookie только
 * тем хостам, которым они выданы, и сохраняет новые. Ни адреса с паролями, ни тела форм в журналы не пишутся.
 * Запросы к одному сайту идут по одному и с паузой ([HostThrottle]), чтобы сайт не принял скачивание за атаку.
 */
class WebHttp(
    private val transport: HttpTransport,
    val cookies: CookieJar,
    private val throttle: HostThrottle = HostThrottle(),
    /** `robots.txt` сайтов: `Crawl-delay` удлиняет паузу между запросами; null — не учитывается. */
    private val robots: RobotsPolicy? = null,
    /** Согласия пользователя на сертификаты сайта (хост[:порт] → разрешение); по умолчанию — никаких. */
    private val certificates: (String) -> CertificateAllowance = { CertificateAllowance.NONE }
) {
    private val preparedHosts = HashSet<String>()

    /** Можно ли открыть [url] автоматически (по расписанию) с точки зрения `robots.txt`; без политики — да. */
    suspend fun allowedByRobots(url: String): Boolean = robots?.isAllowed(url) ?: true

    /** Один раз на сайт читает `robots.txt` и передаёт очереди запросов `Crawl-delay`. */
    private suspend fun prepareHost(url: String) {
        val policy = robots ?: return
        if (url.substringBefore('?').endsWith("/robots.txt")) return
        val host = hostOf(url)
        if (!preparedHosts.add(host)) return
        policy.rulesFor(url).crawlDelayMs?.let { throttle.setCrawlDelay(host, it) }
    }

    val userAgent: String get() = transport.userAgent

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = MAX_PAGE_BYTES): HttpResult =
        send("GET", url, null, headers, maxBytes)

    /**
     * Запрос мимо очереди сайта: только для `robots.txt`, чтобы первая страница сайта не ждала лишнюю паузу после него.
     * Один небольшой запрос на сайт в сутки.
     */
    suspend fun getUnthrottled(url: String, maxBytes: Long = MAX_ROBOTS_BYTES): HttpResult =
        send("GET", url, null, emptyMap(), maxBytes, throttled = false)

    suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        referer: String? = null,
        maxBytes: Long = MAX_PAGE_BYTES
    ): HttpResult {
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        if (referer != null) headers["Referer"] = referer
        return send("POST", url, formEncode(fields).encodeToByteArray(), headers, maxBytes)
    }

    private suspend fun send(
        method: String,
        startUrl: String,
        body: ByteArray?,
        extra: Map<String, String>,
        maxBytes: Long,
        throttled: Boolean = true
    ): HttpResult {
        var url = requireHttp(startUrl)
        NetSafety.checkCleartext(url)
        var verb = method
        var payload = body
        var previous: String? = null
        repeat(MAX_REDIRECTS + 1) {
            var retries = 0
            var response: RawResponse
            if (verb == "GET" && throttled) prepareHost(url)
            while (true) {
                // Заголовки собираются заново на каждую попытку: cookie из ответа 429 / 503 должны уйти с повтором
                val headers = LinkedHashMap<String, String>()
                headers["User-Agent"] = transport.userAgent
                headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                headers["Accept-Language"] = "ru,en;q=0.8"
                headers.putAll(extra)
                // Данные для входа (Basic и др.) предназначены только хосту первого запроса, но не тому, куда он перенаправил
                if (hostOf(url) != hostOf(startUrl)) headers.keys.removeAll { it.lowercase() in CREDENTIAL_HEADERS }
                if (previous != null && "Referer" !in headers) headers["Referer"] = previous!!
                cookies.header(url)?.let { headers["Cookie"] = it }
                if (payload == null) headers.remove("Content-Type")

                val allowance = certificates(hostOf(url))
                val request = RawRequest(
                    url, verb, headers, payload,
                    trustedCertificates = allowance.fingerprints, trustAnyCertificate = allowance.any
                )
                val attempt = if (throttled) throttledExecute(request, maxBytes) else Attempt(transport.execute(request, maxBytes), null)
                response = attempt.response
                response.setCookies.forEach { cookies.store(url, it) }
                // Повторяются только запросы на чтение: форма входа с одноразовым токеном второй раз не отправляется
                val retry = response.status in RATE_LIMITED && verb == "GET" && retries < MAX_RATE_RETRIES &&
                    (attempt.retryAfterMs ?: 0L) <= HostThrottle.MAX_BACKOFF_MS
                if (!retry) break
                retries++
            }

            val location = response.headers["location"]
            if (response.status in REDIRECT_CODES && !location.isNullOrBlank()) {
                val next = WebNavigationDetector.resolveUrl(url, location)?.let(::requireHttp)
                    ?: throw IllegalStateException("Некорректное перенаправление")
                NetSafety.checkCleartext(next)
                NetSafety.checkRedirect(url, next)
                // 307/308 сохраняют и пароль в теле формы, не только заголовки.
                if (payload != null && response.status in listOf(307, 308) &&
                    !sameOrigin(url, next)) {
                    throw UnsafeRedirectException("Пересылка тела запроса на другой origin запрещена")
                }
                // 301/302/303 после POST превращаются в GET; 307/308 повторяют запрос как есть
                if (response.status in listOf(301, 302, 303) && verb != "GET") { verb = "GET"; payload = null }
                previous = url
                url = next
                return@repeat
            }
            return HttpResult(url, response.status, response.headers, response.body)
        }
        throw IllegalStateException("Слишком много перенаправлений")
    }

    private class Attempt(val response: RawResponse, val retryAfterMs: Long?)

    /**
     * Один запрос в очереди своего сайта ([HostThrottle]). О 429 / 503 очередь узнаёт здесь и увеличивает паузу,
     * а повтор (не больше [MAX_RATE_RETRIES] раз, только GET, не при `Retry-After` дольше минуты) решает [send].
     */
    private suspend fun throttledExecute(request: RawRequest, maxBytes: Long): Attempt =
        throttle.turn(hostOf(request.url)) { turn ->
            val response = transport.execute(request, maxBytes)
            if (response.status in RATE_LIMITED) {
                val retryAfter = parseRetryAfterMs(response.headers["retry-after"])
                turn.tooManyRequests(retryAfter)
                Attempt(response, retryAfter)
            } else {
                turn.succeeded()
                Attempt(response, null)
            }
        }

    private fun requireHttp(url: String): String {
        require(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            "Поддерживаются только ссылки http(s)"
        }
        return url
    }

    companion object {
        /** Строгое сравнение origin без нормализации www или смешивания портов. */
        internal fun sameOrigin(first: String, second: String): Boolean {
            fun origin(url: String): String? {
                val scheme = url.substringBefore("://").lowercase()
                if (scheme != "http" && scheme != "https") return null
                val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
                if (authority.isBlank() || '@' in authority || '\\' in authority || authority.any { it.isWhitespace() }) return null
                return "$scheme://$authority"
            }
            val expected = origin(first) ?: return false
            return expected == origin(second)
        }

        const val MAX_REDIRECTS = 10
        const val MAX_PAGE_BYTES = 12L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 150L * 1024 * 1024
        const val MAX_RATE_RETRIES = 2
        const val MAX_ROBOTS_BYTES = 512L * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val RATE_LIMITED = setOf(429, 503)
        private val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization")

        /** Сайт (с портом) из адреса — ключ очереди запросов. */
        internal fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').lowercase()

        /** `Retry-After` в секундах → миллисекунды; дата и мусор дают null. */
        internal fun parseRetryAfterMs(value: String?): Long? =
            value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }

        /** application/x-www-form-urlencoded: UTF-8, пробел → «+». */
        fun formEncode(fields: List<Pair<String, String>>): String =
            fields.joinToString("&") { (k, v) -> percentEncode(k) + "=" + percentEncode(v) }

        private fun percentEncode(s: String): String {
            val out = StringBuilder()
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                when {
                    c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '*'.code ->
                        out.append(c.toChar())
                    c == ' '.code -> out.append('+')
                    else -> out.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
                }
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
