package com.localdoc.ai.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Бережная очередь запросов: к каждому сайту идёт не больше одного запроса одновременно, между запросами
 * выдерживается пауза с небольшим случайным разбросом, а после ответа «слишком много запросов» (429 / 503)
 * пауза растёт и медленно возвращается к обычной. Через случайное число запросов (каждый раз новое) сайт получает
 * длинный отдых; скорость выбирает пользователь ([DownloadSpeed]), а `Crawl-delay` сайта только удлиняет паузу. Так сайты не принимают скачивание за атаку и не блокируют.
 * Часы, случайность и ожидание подменяются в тестах.
 */
class HostThrottle(
    /** Фиксированная пауза вместо паузы режима скорости (нужна тестам); null — берётся из [speed]. */
    private val intervalMs: Long? = null,
    /** Режим «Скорость скачивания»: читается на каждом запросе, поэтому смена в настройках действует сразу. */
    private val speed: () -> DownloadSpeed = { DownloadSpeed.CAUTIOUS },
    private val now: () -> Long = MONOTONIC_MILLIS,
    private val random: () -> Double = { Random.nextDouble() },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    /** Очередь одного сайта; поля меняются только под [lock]. */
    private class HostState(var untilRest: Int) {
        val lock = Mutex()
        var nextAt = 0L
        var slowdown = 1

        /** Минимальная пауза из `Crawl-delay` в robots.txt сайта. */
        var crawlDelayMs = 0L
    }

    /** Итог запроса, о котором запрос сообщает очереди. */
    class Turn internal constructor() {
        internal var backoffMs: Long? = null
        internal var succeeded = false

        /** Сайт просит подождать; [retryAfterMs] — значение заголовка `Retry-After`, если он был. */
        fun tooManyRequests(retryAfterMs: Long?) {
            backoffMs = retryAfterMs ?: 0L
            succeeded = false
        }

        fun succeeded() {
            backoffMs = null
            succeeded = true
        }
    }

    private val hosts = HashMap<String, HostState>()
    private val registry = Mutex()

    /** Сайт [host] просит не чаще одного запроса в [delayMs] (`Crawl-delay`); 0 — без требования. */
    suspend fun setCrawlDelay(host: String, delayMs: Long) {
        val state = registry.withLock { hosts.getOrPut(host) { HostState(speed().requestsUntilRest(random())) } }
        state.lock.withLock { state.crawlDelayMs = delayMs.coerceIn(0L, MAX_CRAWL_DELAY_MS) }
    }

    /** Выполняет [block] в очереди сайта [host]: ждёт своей очереди и паузы после предыдущего запроса. */
    suspend fun <T> turn(host: String, block: suspend (Turn) -> T): T {
        val state = registry.withLock { hosts.getOrPut(host) { HostState(speed().requestsUntilRest(random())) } }
        return state.lock.withLock {
            val wait = state.nextAt - now()
            if (wait > 0) sleep(wait)
            val turn = Turn()
            try {
                block(turn)
            } finally {
                schedule(state, turn)
            }
        }
    }

    private fun schedule(state: HostState, turn: Turn) {
        val backoff = turn.backoffMs
        if (backoff != null) {
            state.slowdown = (state.slowdown * 2).coerceAtMost(MAX_SLOWDOWN)
        } else if (turn.succeeded && state.slowdown > 1) {
            state.slowdown--
        }
        val current = speed()
        val regular = jittered((intervalMs ?: current.intervalMs) * state.slowdown)
        // Через случайное число запросов сайт получает длинный отдых, после чего счёт начинается заново
        val rest = if (--state.untilRest <= 0) {
            state.untilRest = current.requestsUntilRest(random())
            jittered(current.restMs)
        } else 0L
        val base = maxOf(regular, rest, state.crawlDelayMs)
        val pause = if (backoff != null) maxOf(backoff.coerceAtMost(MAX_BACKOFF_MS), base) else base
        state.nextAt = now() + pause
    }

    private fun jittered(ms: Long): Long = (ms * (1.0 - JITTER + 2 * JITTER * random())).toLong()

    companion object {
        /** Значения режима «Осторожно» — по умолчанию. */
        const val DEFAULT_INTERVAL_MS = 10_000L
        const val REST_AFTER_MIN = 12
        const val REST_AFTER_MAX = 31
        const val REST_MS = 60_000L

        /** Сколько запросов пройдёт до следующего длинного отдыха в режиме «Осторожно». */
        fun requestsUntilRest(random: Double): Int = DownloadSpeed.CAUTIOUS.requestsUntilRest(random)

        const val MAX_BACKOFF_MS = 60_000L

        /** Дольше двух минут между запросами по `Crawl-delay` не ждём: такие значения — ошибка или защита от ботов. */
        const val MAX_CRAWL_DELAY_MS = 120_000L
        private const val MAX_SLOWDOWN = 8
        private const val JITTER = DownloadSpeed.JITTER
        private val ORIGIN = TimeSource.Monotonic.markNow()
        private val MONOTONIC_MILLIS: () -> Long = { ORIGIN.elapsedNow().inWholeMilliseconds }
    }
}
