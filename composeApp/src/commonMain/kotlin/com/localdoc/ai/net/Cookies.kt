package com.localdoc.ai.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Хранилище секретов, зашифрованное платформой ([com.localdoc.ai.platform.SecureStore]); в тестах — в памяти. */
interface SecretStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Cookie сессии. [hostOnly] — без атрибута Domain: отправляется только на тот же хост. */
@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAt: Long,
    val secure: Boolean = false,
    val hostOnly: Boolean = true
) {
    // Значение cookie — секрет сессии: в строковое представление (логи, отладка) оно не попадает
    override fun toString() = "StoredCookie($name@$domain)"
}

/**
 * Cookie сайтов. Хранятся только в [SecretStorage] (зашифрованы), отправляются строго на те хосты,
 * для которых выданы, и только по HTTPS, если cookie помечена `Secure`. Сессионные cookie живут
 * [SESSION_LIFETIME_MS], чтобы вход сохранялся между запусками приложения.
 */
class CookieJar(private val storage: SecretStorage, private val now: () -> Long = ::nowMillis) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredCookie.serializer())
    private var cookies: List<StoredCookie> = load()

    // Загрузка глав идёт в несколько потоков: изменения cookie защищены короткой блокировкой
    private val mutex = Mutex()
    private inline fun <T> locked(block: () -> T): T {
        while (!mutex.tryLock()) { /* критическая секция короткая — ждём активно */ }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private fun load(): List<StoredCookie> =
        storage.get(KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun save() {
        if (cookies.isEmpty()) storage.remove(KEY) else storage.put(KEY, json.encodeToString(serializer, cookies))
    }

    /** Значение заголовка `Cookie` для запроса на [url] либо null. */
    private fun headerImpl(url: String): String? {
        val host = hostOf(url)
        val path = pathOf(url)
        val https = url.startsWith("https://", ignoreCase = true)
        val time = now()
        val matching = cookies.filter { it.expiresAt > time && (!it.secure || https) && domainMatches(host, it) && pathMatches(path, it.path) }
            .sortedByDescending { it.path.length }
        return matching.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** Разбор заголовка `Set-Cookie` ответа на [url]; чужие домены и «публичные суффиксы» отвергаются. */
    private fun storeImpl(url: String, setCookie: String) {
        val host = hostOf(url)
        val parts = setCookie.split(';').map { it.trim() }
        val first = parts.firstOrNull()?.split('=', limit = 2)?.takeIf { it.size == 2 && it[0].isNotBlank() } ?: return
        val name = first[0].trim()
        val value = first[1].trim()
        var domain = host
        var hostOnly = true
        var path = defaultPath(pathOf(url))
        var maxAge: Long? = null
        var expires: Long? = null
        var secure = false
        for (attr in parts.drop(1)) {
            val key = attr.substringBefore('=').trim().lowercase()
            val v = attr.substringAfter('=', "").trim()
            when (key) {
                "domain" -> {
                    val d = v.removePrefix(".").lowercase()
                    // Domain обязан покрывать хост ответа и не быть «com» / «co.uk»
                    if (d.contains('.') && (host == d || host.endsWith(".$d"))) { domain = d; hostOnly = false } else return
                }
                "path" -> if (v.startsWith("/")) path = v
                "max-age" -> maxAge = v.toLongOrNull()
                "expires" -> expires = parseHttpDate(v)
                "secure" -> secure = true
            }
        }
        val time = now()
        val expiresAt = when {
            maxAge != null -> time + maxAge * 1000
            expires != null -> expires
            else -> time + SESSION_LIFETIME_MS
        }
        val rest = cookies.filterNot { it.name == name && it.domain == domain && it.path == path && it.hostOnly == hostOnly }
        cookies = if (expiresAt <= time) rest else (rest + StoredCookie(name, value, domain, path, expiresAt, secure, hostOnly)).takeLast(MAX_COOKIES)
        save()
    }

    /** Cookie из встроенного браузера: строка `a=b; c=d` для хоста [url] (атрибутов у неё нет). */
    private fun importHeaderImpl(url: String, cookieHeader: String) {
        val host = hostOf(url)
        val time = now()
        val imported = cookieHeader.split(';').mapNotNull { pair ->
            val kv = pair.trim().split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                StoredCookie(kv[0].trim(), kv[1].trim(), host, "/", time + SESSION_LIFETIME_MS, url.startsWith("https://"), true)
            } else null
        }
        val kept = cookies.filterNot { old -> imported.any { it.name == old.name && it.domain == old.domain && it.hostOnly == old.hostOnly } }
        cookies = (kept + imported).takeLast(MAX_COOKIES)
        save()
    }

    private fun hasCookiesImpl(host: String): Boolean {
        val h = normalizeHost(host)
        val time = now()
        return cookies.any { it.expiresAt > time && domainMatches(h, it) }
    }

    /** Забыть сайт: удаляет все cookie хоста и его родительских доменов, которыми он пользуется. */
    private fun clearImpl(host: String) {
        val h = normalizeHost(host)
        cookies = cookies.filterNot { domainMatches(h, it) || it.domain == h || it.domain.endsWith(".$h") }
        save()
    }

    private fun clearAllImpl() {
        cookies = emptyList()
        save()
    }

    fun header(url: String): String? = locked { headerImpl(url) }
    fun store(url: String, setCookie: String) = locked { storeImpl(url, setCookie) }
    fun importHeader(url: String, cookieHeader: String) = locked { importHeaderImpl(url, cookieHeader) }
    fun hasCookies(host: String): Boolean = locked { hasCookiesImpl(host) }
    fun clear(host: String) = locked { clearImpl(host) }
    fun clearAll() = locked { clearAllImpl() }

    private fun domainMatches(host: String, cookie: StoredCookie) =
        if (cookie.hostOnly) host == cookie.domain else host == cookie.domain || host.endsWith("." + cookie.domain)

    private fun pathMatches(requestPath: String, cookiePath: String) =
        requestPath == cookiePath ||
            (requestPath.startsWith(cookiePath) && (cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/'))

    private fun defaultPath(path: String): String {
        if (!path.startsWith("/") || path.count { it == '/' } <= 1) return "/"
        return path.substringBeforeLast('/')
    }

    companion object {
        private const val KEY = "cookies_v1"
        const val SESSION_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_COOKIES = 3000

        fun hostOf(url: String): String =
            normalizeHost(url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':'))

        fun normalizeHost(host: String): String = host.trim().lowercase().removePrefix("www.")

        private fun pathOf(url: String): String {
            val afterHost = url.substringAfter("://").substringAfter('/', "")
            return "/" + afterHost.substringBefore('?').substringBefore('#')
        }

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val DATE = Regex("(\\d{1,2})[ -]([A-Za-z]{3})[A-Za-z]*[ -](\\d{2,4}) (\\d{1,2}):(\\d{2}):(\\d{2})")

        /** «Wed, 21 Oct 2026 07:28:00 GMT» и «Wed, 21-Oct-26 07:28:00 GMT» → миллисекунды эпохи UTC. */
        internal fun parseHttpDate(s: String): Long? {
            val m = DATE.find(s) ?: return null
            val day = m.groupValues[1].toInt()
            val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
            if (month == 0) return null
            var year = m.groupValues[3].toInt()
            if (year < 100) year += if (year < 70) 2000 else 1900
            val seconds = daysFromCivil(year, month, day) * 86400 +
                m.groupValues[4].toInt() * 3600 + m.groupValues[5].toInt() * 60 + m.groupValues[6].toInt()
            return seconds * 1000
        }

        // Алгоритм Говарда Хиннанта: дни от 1970-01-01 до даты григорианского календаря
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era.toLong() * 146097 + doe - 719468
        }
    }
}
