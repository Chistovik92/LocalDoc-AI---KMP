package com.localdoc.ai.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Разобранный `robots.txt` для нашего имени агента: правила `Allow` / `Disallow` (с `*` и `$`) и `Crawl-delay`.
 * Выбирается группа с самым подходящим именем агента, иначе `*`; из правил побеждает самый длинный шаблон,
 * при равенстве — `Allow`. Пустой `Disallow` ничего не запрещает.
 */
class RobotsTxt private constructor(private val rules: List<Rule>, val crawlDelayMs: Long?) {

    private class Rule(val allow: Boolean, val pattern: String)

    /** [pathAndQuery] — путь с запросом, как в адресе после имени сайта: `/book/12?page=2`. */
    fun isAllowed(pathAndQuery: String): Boolean {
        val path = pathAndQuery.ifEmpty { "/" }
        var best: Rule? = null
        for (rule in rules) {
            if (!matches(rule.pattern, path)) continue
            val current = best
            if (current == null || rule.pattern.length > current.pattern.length ||
                (rule.pattern.length == current.pattern.length && rule.allow && !current.allow)
            ) best = rule
        }
        return best?.allow ?: true
    }

    companion object {
        val ALLOW_ALL = RobotsTxt(emptyList(), null)

        /** Имя продукта в `User-agent`: сайт может обратиться к нам по нему отдельно от `*`. */
        const val AGENT = "localdocai"

        fun parse(text: String, agent: String = AGENT): RobotsTxt {
            class Group(val agents: MutableList<String> = ArrayList(), val rules: MutableList<Rule> = ArrayList(), var delay: Double? = null)

            val groups = ArrayList<Group>()
            var current: Group? = null
            var collectingAgents = false
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val field = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (field) {
                    "user-agent" -> {
                        val group = if (!collectingAgents || current == null) Group().also { groups.add(it) } else current
                        group.agents.add(value.lowercase())
                        current = group
                        collectingAgents = true
                    }
                    "allow", "disallow" -> {
                        collectingAgents = false
                        if (value.isNotEmpty()) current?.rules?.add(Rule(field == "allow", value))
                    }
                    "crawl-delay" -> {
                        collectingAgents = false
                        current?.delay = value.toDoubleOrNull()?.takeIf { it >= 0 }
                    }
                    else -> collectingAgents = false
                }
            }
            val name = agent.lowercase()
            // Группа с самым длинным подходящим именем (частичное совпадение по имени продукта), иначе «*»
            val specific = groups.mapNotNull { g ->
                g.agents.filter { it != "*" && it.isNotEmpty() && name.contains(it) }.maxOfOrNull { it.length }?.let { len -> len to g }
            }.maxByOrNull { it.first }?.second
            val star = groups.filter { "*" in it.agents }
            val chosen = specific
                ?: star.takeIf { it.isNotEmpty() }?.let { all ->
                    Group(rules = all.flatMap { it.rules }.toMutableList(), delay = all.firstNotNullOfOrNull { it.delay })
                }
                ?: return ALLOW_ALL
            return RobotsTxt(chosen.rules, chosen.delay?.let { (it * 1000).toLong() })
        }

        /** Шаблон с `*` (любые символы) и `$` (конец пути) в начале пути. */
        internal fun matches(pattern: String, path: String): Boolean {
            val anchored = pattern.endsWith("$")
            val body = if (anchored) pattern.dropLast(1) else pattern
            return matchFrom(body, 0, path, 0, anchored)
        }

        private fun matchFrom(pattern: String, pi: Int, path: String, si: Int, anchored: Boolean): Boolean {
            var p = pi
            var s = si
            while (p < pattern.length) {
                if (pattern[p] == '*') {
                    while (p < pattern.length && pattern[p] == '*') p++
                    if (p == pattern.length) return true
                    for (start in s..path.length) if (matchFrom(pattern, p, path, start, anchored)) return true
                    return false
                }
                if (s >= path.length || path[s] != pattern[p]) return false
                p++
                s++
            }
            return !anchored || s == path.length
        }
    }
}

/**
 * `robots.txt` сайтов: загружается один раз на сайт и хранится сутки (при сбое — час, считая сайт открытым).
 * Запросы за самим файлом идут через [fetch] — обычный клиент с очередью запросов.
 */
class RobotsPolicy(
    private val fetch: suspend (robotsUrl: String) -> HttpResult?,
    private val agent: String = RobotsTxt.AGENT,
    private val now: () -> Long = MONOTONIC_MILLIS
) {
    private class Entry(val rules: RobotsTxt, val expiresAt: Long)

    private val cache = HashMap<String, Entry>()
    private val lock = Mutex()

    /** Правила сайта, которому принадлежит [url]. */
    suspend fun rulesFor(url: String): RobotsTxt {
        val origin = originOf(url)
        return lock.withLock {
            cache[origin]?.takeIf { it.expiresAt > now() }?.rules ?: load(origin).also { cache[origin] = it }.rules
        }
    }

    private suspend fun load(origin: String): Entry {
        val result = try {
            fetch("$origin/robots.txt")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when {
            result != null && result.isOk -> Entry(RobotsTxt.parse(result.text(), agent), now() + DAY_MS)
            // 404 и подобное — правил нет; сбой сети или 5xx — пока считаем сайт открытым, но спросим снова скоро
            result != null && result.status in 400..499 -> Entry(RobotsTxt.ALLOW_ALL, now() + DAY_MS)
            else -> Entry(RobotsTxt.ALLOW_ALL, now() + HOUR_MS)
        }
    }

    /** Можно ли открыть [url] автоматически (проверка новых глав по расписанию). */
    suspend fun isAllowed(url: String): Boolean = rulesFor(url).isAllowed(pathOf(url))

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        private val ORIGIN = TimeSource.Monotonic.markNow()
        private val MONOTONIC_MILLIS: () -> Long = { ORIGIN.elapsedNow().inWholeMilliseconds }

        internal fun originOf(url: String): String {
            val scheme = url.substringBefore("://").lowercase()
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').lowercase()
            return "$scheme://$host"
        }

        internal fun pathOf(url: String): String {
            val rest = url.substringAfter("://").substringBefore('#')
            val slash = rest.indexOfFirst { it == '/' || it == '?' }
            if (slash < 0) return "/"
            return rest.substring(slash).let { if (it.startsWith("?")) "/$it" else it }
        }
    }
}
