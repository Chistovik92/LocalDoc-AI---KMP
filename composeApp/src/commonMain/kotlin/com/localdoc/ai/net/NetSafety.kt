package com.localdoc.ai.net

/** Перенаправление отвергнуто: ведёт во внутреннюю сеть устройства или с https на открытый http. */
class UnsafeRedirectException(message: String) : IllegalStateException(message)

/**
 * Защита от SSRF и понижения защиты соединения (аудит 2.0.0). Сайт из интернета не должен мочь «перенаправить»
 * приложение на адрес самого телефона или домашней сети (метаданные облака, роутер, локальные службы), а
 * https-страница — увести на открытый http. Адрес, который пользователь ввёл сам (каталог OPDS на NAS, WebDAV
 * дома), остаётся доступным: проверяются только переходы с публичного хоста.
 * DNS-перепривязка (публичное имя, которое резолвится во внутренний адрес) здесь не ловится — это ограничение
 * проверки по имени, описано в docs/SECURITY_AUDIT.md.
 */
object NetSafety {

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
