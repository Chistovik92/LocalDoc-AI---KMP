package com.localdoc.ai.net

/** Перенаправление отвергнуто: ведёт во внутреннюю сеть устройства или с https на открытый http. */
class UnsafeRedirectException(message: String) : IllegalStateException(message)

/**
 * Защита от SSRF и понижения защиты соединения (аудит 2.0.0). Сайт из интернета не должен мочь «перенаправить»
 * приложение на адрес самого телефона или домашней сети (метаданные облака, роутер, локальные службы), а
 * https-страница — увести на открытый http. Адрес, который пользователь ввёл сам (каталог OPDS на NAS, WebDAV
 * дома), остаётся доступным: проверяются только переходы с публичного хоста.
 *
 * Защита от DNS-перепривязки (аудит 2.0.0, пункт 10): публичное имя может резолвиться во внутренний адрес,
 * поэтому соединение проверяется по IP, к которому оно реально идёт, — см. [checkResolved]. Остаточный риск —
 * «ротация» DNS (первый запрос публичный, второй внутренний): платформенный клиент резолвит имя повторно.
 */
object NetSafety {

    /** Соединение отвергнуто защитой от DNS-перепривязки: публичное имя резолвится во внутренний адрес. */
    class DnsRebindingException(message: String) : IllegalStateException(message)

    /** Открытый http:// без согласия пользователя (каталоги домашней сети — только с явного разрешения). */
    class CleartextNotAllowedException(message: String) : IllegalStateException(message)

    /** Согласие пользователя на открытый http:// для домашних каталогов (OPDS/WebDAV). Выставляется настройкой. */
    var cleartextAllowed: Boolean = false

    /** Хост из адреса: нижний регистр, без схемы, логина, порта, скобок IPv6 и точки в конце. */
    fun hostOf(url: String): String {
        val authority = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']') else hostPort.substringBefore(':')
        return host.lowercase().trimEnd('.')
    }

    /** Адрес самого устройства или частной сети: loopback, link-local, RFC 1918, CGNAT, `.local`, ULA IPv6. */
    fun isInternalHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().trimEnd('.').substringBefore('%')
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return true
        if (':' in host) return ipv6Internal(host)
        val ip = parseIpv4(host) ?: return false
        return ipv4Internal(ip)
    }

    /** Внутренний ли IP-адрес (текст `a.b.c.d` или IPv6): та же проверка, что и по имени в [isInternalHost]. */
    fun isInternalAddress(raw: String): Boolean {
        val text = raw.substringBefore('%').trim().trimEnd('.')
        if (text.isEmpty()) return true
        if (':' in text) return ipv6Internal(text)
        val ip = parseIpv4(text) ?: return false
        return ipv4Internal(ip)
    }

    /**
     * Защита от DNS-перепривязки: соединение проверяется по IP, к которому оно реально идёт, а не по имени.
     * Публичное имя, которое резолвится во внутренний адрес (127.0.0.1, 192.168.x, роутер), отвергается:
     * чужой сайт не получит доступа к домашней сети телефона. Адрес, который пользователь ввёл сам
     * (каталог на NAS, WebDAV дома — внутреннее имя), не проверяется: внутренний IP для него ожидаем.
     */
    fun checkResolved(host: String, addresses: List<String>) {
        if (isInternalHost(host)) return
        if (addresses.any(::isInternalAddress)) {
            throw DnsRebindingException("Адрес $host указывает во внутреннюю сеть — соединение заблокировано")
        }
    }

    /**
     * Открытый http:// разрешён только пользователю, который сам его попросил: для хостов домашней сети —
     * после явного согласия в настройках ([cleartextAllowed]). Публичные сайты по http не открываются:
     * пароли и сессии не должны уходить по открытому каналу, а https-страница не может уйти на http
     * (см. [checkRedirect]). Вызывается перед каждым соединением.
     */
    fun checkCleartext(url: String) {
        if (!url.startsWith("http://", ignoreCase = true)) return
        val host = hostOf(url)
        if (isInternalHost(host) && cleartextAllowed) return
        if (isInternalHost(host)) {
            throw CleartextNotAllowedException(
                "Открытый http:// для домашней сети запрещён: разрешите его в настройках «Каталоги и синхронизация»"
            )
        }
        throw CleartextNotAllowedException("Открытый http:// для сайтов интернета не используется: нужен https://")
    }

    /** Бросает [UnsafeRedirectException], если переход [from] → [to] небезопасен. */
    fun checkRedirect(from: String, to: String) {
        val fromInternal = isInternalHost(hostOf(from))
        val toInternal = isInternalHost(hostOf(to))
        if (toInternal && !fromInternal) {
            throw UnsafeRedirectException("Сайт перенаправил на внутренний адрес — переход заблокирован")
        }
        val downgrade = from.startsWith("https://", ignoreCase = true) && to.startsWith("http://", ignoreCase = true)
        if (downgrade && !toInternal) {
            throw UnsafeRedirectException("Сайт перенаправил с https на незащищённый http — переход заблокирован")
        }
    }

    /** Все допустимые записи IPv4: `a.b.c.d`, `a.b.c`, `a.b`, одно число; части — десятичные, `0x…` или восьмеричные. */
    private fun parseIpv4(host: String): Long? {
        val parts = host.split('.')
        if (parts.size !in 1..4) return null
        val numbers = parts.map { parseNumber(it) ?: return null }
        var value = 0L
        for (i in 0 until numbers.size - 1) {
            if (numbers[i] !in 0..255) return null
            value = (value shl 8) or numbers[i]
        }
        val restBytes = 4 - (numbers.size - 1)
        val last = numbers.last()
        if (last < 0 || last >= (1L shl (8 * restBytes))) return null
        return (value shl (8 * restBytes)) or last
    }

    private fun parseNumber(part: String): Long? {
        if (part.isEmpty()) return null
        return when {
            part.startsWith("0x", ignoreCase = true) -> part.drop(2).takeIf { it.isNotEmpty() }?.toLongOrNull(16)
            part.length > 1 && part.startsWith("0") -> part.toLongOrNull(8)
            else -> part.toLongOrNull()?.takeIf { part.all(Char::isDigit) }
        }
    }

    private fun ipv4Internal(ip: Long): Boolean {
        val a = (ip shr 24).toInt() and 0xFF
        val b = (ip shr 16).toInt() and 0xFF
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    private fun ipv6Internal(host: String): Boolean {
        val groups = parseIpv6(host) ?: return false
        if (groups.all { it == 0 } || (groups.take(7).all { it == 0 } && groups[7] == 1)) return true
        if (groups[0] and 0xFE00 == 0xFC00) return true
        if (groups[0] and 0xFFC0 == 0xFE80) return true
        if (groups.take(5).all { it == 0 } && groups[5] == 0xFFFF) {
            return ipv4Internal(((groups[6].toLong() shl 16) or groups[7].toLong()))
        }
        return false
    }

    private fun parseIpv6(host: String): IntArray? {
        val sides = host.split("::")
        if (sides.size > 2) return null
        fun groupsOf(text: String): List<Int>? {
            if (text.isEmpty()) return emptyList()
            val result = ArrayList<Int>()
            val pieces = text.split(':')
            for ((index, piece) in pieces.withIndex()) {
                if ('.' in piece) {
                    if (index != pieces.lastIndex) return null
                    val ip = parseIpv4(piece) ?: return null
                    result.add((ip shr 16).toInt() and 0xFFFF)
                    result.add(ip.toInt() and 0xFFFF)
                } else {
                    result.add(piece.toIntOrNull(16)?.takeIf { it in 0..0xFFFF } ?: return null)
                }
            }
            return result
        }
        val head = groupsOf(sides[0]) ?: return null
        if (sides.size == 1) return if (head.size == 8) head.toIntArray() else null
        val tail = groupsOf(sides[1]) ?: return null
        if (head.size + tail.size > 7) return null
        return (head + List(8 - head.size - tail.size) { 0 } + tail).toIntArray()
    }
}
