package com.localdoc.ai.net

import com.localdoc.ai.platform.RawResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Очередь запросов на сайт: паузы, независимость сайтов, 429 / Retry-After. Время — своё, виртуальное. */
class HostThrottleTest {

    private class Setup(handler: () -> RawResponse) {
        var now = 0L
        val times = ArrayList<Long>()
        val transport = FakeTransport { _ ->
            times.add(now)
            handler()
        }

        // random = 0.5 → разброс ровно 1,0: пауза без случайности
        val throttle = HostThrottle(intervalMs = 1000, now = { now }, random = { 0.5 }, sleep = { now += it })
        val http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, throttle)
    }

    @Test
    fun `requests to one site are spaced by the interval`() = runTest {
        val s = Setup { page("ok") }
        repeat(3) { s.http.get("https://novel.example/ch$it") }
        assertEquals(listOf(0L, 1000L, 2000L), s.times)
    }

    @Test
    fun `default pause between chapters is at least seven seconds even with the smallest jitter`() = runTest {
        var now = 0L
        val times = ArrayList<Long>()
        val transport = FakeTransport { _ ->
            times.add(now)
            page("ok")
        }
        // random = 0.0 → самый малый разброс: пауза по умолчанию, умноженная на (1 − разброс)
        val throttle = HostThrottle(now = { now }, random = { 0.0 }, sleep = { now += it })
        val http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, throttle)
        repeat(3) { http.get("https://novel.example/ch$it") }
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= 7_000L }, "паузы между главами: $gaps")
    }

    @Test
    fun `the number of requests before a long rest is random but bounded`() {
        assertEquals(HostThrottle.REST_AFTER_MIN, HostThrottle.requestsUntilRest(0.0))
        assertEquals(HostThrottle.REST_AFTER_MAX, HostThrottle.requestsUntilRest(0.9999))
        assertTrue(HostThrottle.requestsUntilRest(0.5) in HostThrottle.REST_AFTER_MIN..HostThrottle.REST_AFTER_MAX)
        assertTrue(HostThrottle.REST_AFTER_MIN != HostThrottle.REST_AFTER_MAX)
    }

    @Test
    fun `after the chosen number of requests the site gets one long rest`() = runTest {
        val s = Setup { page("ok") }
        val n = HostThrottle.requestsUntilRest(0.5)
        repeat(n + 3) { s.http.get("https://novel.example/ch$it") }
        val gaps = s.times.zipWithNext { a, b -> b - a }
        assertEquals(HostThrottle.REST_MS, gaps[n - 1], "отдых после $n-го запроса, паузы: $gaps")
        assertTrue(gaps.filterIndexed { i, _ -> i != n - 1 }.all { it == 1000L }, "остальные паузы обычные: $gaps")
    }

    @Test
    fun `different sites do not wait for each other`() = runTest {
        val s = Setup { page("ok") }
        s.http.get("https://a.example/1")
        s.http.get("https://b.example/1")
        assertEquals(listOf(0L, 0L), s.times)
    }

    @Test
    fun `too many requests waits for Retry-After and retries`() = runTest {
        var calls = 0
        val s = Setup { if (calls++ == 0) page("slow down", status = 429, headers = mapOf("retry-after" to "5")) else page("ok") }
        val result = s.http.get("https://novel.example/ch1")
        assertEquals(200, result.status)
        assertEquals(2, s.transport.requests.size)
        assertTrue(s.times[1] - s.times[0] >= 5000, "ждали ${s.times[1] - s.times[0]} мс")
    }

    @Test
    fun `gives up after the retry limit and returns the 429`() = runTest {
        val s = Setup { page("no", status = 429) }
        val result = s.http.get("https://novel.example/ch1")
        assertEquals(429, result.status)
        assertEquals(WebHttp.MAX_RATE_RETRIES + 1, s.transport.requests.size)
    }

    @Test
    fun `does not wait when the site asks for more than a minute`() = runTest {
        val s = Setup { page("no", status = 429, headers = mapOf("retry-after" to "3600")) }
        val result = s.http.get("https://novel.example/ch1")
        assertEquals(429, result.status)
        assertEquals(1, s.transport.requests.size)
    }

    @Test
    fun `pause grows after 429 and relaxes after successes`() = runTest {
        var calls = 0
        val s = Setup { if (calls++ == 0) page("no", status = 429) else page("ok") }
        s.http.get("https://novel.example/a") // 429 → повтор через удвоенную паузу (2000), затем успех
        s.http.get("https://novel.example/b")
        s.http.get("https://novel.example/c")
        assertEquals(listOf(0L, 2000L, 3000L, 4000L), s.times)
    }

    @Test
    fun `cookie from a 429 response is sent with the retry`() = runTest {
        var calls = 0
        val s = Setup { if (calls++ == 0) page("no", status = 429, cookies = listOf("sid=abc; Path=/")) else page("ok") }
        val result = s.http.get("https://novel.example/ch1")
        assertEquals(200, result.status)
        assertNull(s.transport.requests[0].headers["Cookie"])
        assertEquals("sid=abc", s.transport.requests[1].headers["Cookie"])
    }

    @Test
    fun `form posts are never replayed automatically`() = runTest {
        val s = Setup { page("no", status = 429) }
        val result = s.http.postForm("https://novel.example/login", listOf("user" to "a"))
        assertEquals(429, result.status)
        assertEquals(1, s.transport.requests.size)
    }

    @Test
    fun `retry after parses seconds only`() {
        assertEquals(5000L, WebHttp.parseRetryAfterMs(" 5 "))
        assertNull(WebHttp.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(WebHttp.parseRetryAfterMs(null))
        assertNull(WebHttp.parseRetryAfterMs("-3"))
    }

    @Test
    fun `host key ignores path user info and case`() {
        assertEquals("novel.example:8080", WebHttp.hostOf("https://user:pw@Novel.Example:8080/a/b?c=d#e"))
    }
}
