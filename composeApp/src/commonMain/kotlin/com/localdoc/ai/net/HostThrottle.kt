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
 * длинный отдых. Так сайты не принимают скачивание за атаку и не блокируют.
 * Часы, случайность и ожидание подменяются в тестах.
 */
class HostThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val now: () -> Long = MONOTONIC_MILLIS,
    private val random: () -> Double = { Random.nextDouble() },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    /** Очередь одного сайта; поля меняются только под [lock]. */
    private class HostState(var untilRest: Int) {
        val lock = Mutex()
        var nextAt = 0L
        var slowdown = 1
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

    /** Выполняет [block] в очереди сайта [host]: ждёт своей очереди и паузы после предыдущего запроса. */
    suspend fun <T> turn(host: String, block: suspend (Turn) -> T): T {
        val state = registry.withLock { hosts.getOrPut(host) { HostState(requestsUntilRest(random())) } }
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
        val regular = jittered(intervalMs * state.slowdown)
        // Через случайное число запросов сайт получает длинный отдых, после чего счёт начинается заново
        val rest = if (--state.untilRest <= 0) {
            state.untilRest = requestsUntilRest(random())
            jittered(REST_MS)
        } else 0L
        val base = maxOf(regular, rest)
        val pause = if (backoff != null) maxOf(backoff.coerceAtMost(MAX_BACKOFF_MS), base) else base
        state.nextAt = now() + pause
    }

    private fun jittered(ms: Long): Long = (ms * (1.0 - JITTER + 2 * JITTER * random())).toLong()

    companion object {
        /** Десять секунд между запросами к одному сайту (с разбросом ±30 %): книга качается долго, но не «по метроному». */
        const val DEFAULT_INTERVAL_MS = 10_000L

        /** Длинный отдых через случайное число запросов, каждый раз новое: от [REST_AFTER_MIN] до [REST_AFTER_MAX]. */
        const val REST_AFTER_MIN = 12
        const val REST_AFTER_MAX = 31
        const val REST_MS = 60_000L

        /** Сколько запросов пройдёт до следующего длинного отдыха; [random] — число от 0 (включая) до 1 (не включая). */
        fun requestsUntilRest(random: Double): Int =
            (REST_AFTER_MIN + (random * (REST_AFTER_MAX - REST_AFTER_MIN + 1)).toInt()).coerceIn(REST_AFTER_MIN, REST_AFTER_MAX)

        const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_SLOWDOWN = 8
        private const val JITTER = 0.3
        private val ORIGIN = TimeSource.Monotonic.markNow()
        private val MONOTONIC_MILLIS: () -> Long = { ORIGIN.elapsedNow().inWholeMilliseconds }
    }
}
