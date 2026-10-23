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
