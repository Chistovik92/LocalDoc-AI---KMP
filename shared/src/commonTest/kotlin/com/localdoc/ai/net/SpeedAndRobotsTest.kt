package com.localdoc.ai.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Режимы «Скорость скачивания» и учёт robots.txt (Crawl-delay, Disallow). */
class SpeedAndRobotsTest {

    private fun gaps(speed: DownloadSpeed, random: Double, count: Int = 3): List<Long> {
        var now = 0L
        val times = ArrayList<Long>()
        val transport = FakeTransport { _ -> times.add(now); page("ok") }
        val throttle = HostThrottle(speed = { speed }, now = { now }, random = { random }, sleep = { now += it })
        val http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, throttle)
        runTest { repeat(count) { http.get("https://novel.example/ch$it") } }
        return times.zipWithNext { a, b -> b - a }
    }

    @Test
    fun `cautious is the default and keeps ten seconds with thirty percent jitter`() {
        assertEquals(DownloadSpeed.CAUTIOUS, DownloadSpeed.entries.first())
        assertEquals(10_000L, DownloadSpeed.CAUTIOUS.intervalMs)
        assertEquals(12, DownloadSpeed.CAUTIOUS.restAfterMin)
        assertEquals(31, DownloadSpeed.CAUTIOUS.restAfterMax)
        assertEquals(60_000L, DownloadSpeed.CAUTIOUS.restMs)
        assertTrue(gaps(DownloadSpeed.CAUTIOUS, 0.0).all { it >= 7_000L })
    }

    @Test
    fun `no mode is faster than 1_2 seconds even with the smallest jitter`() {
        for (speed in DownloadSpeed.entries) {
            val gaps = gaps(speed, 0.0)
            assertTrue(gaps.all { it >= DownloadSpeed.MIN_GAP_MS }, "$speed: $gaps")
        }
    }

    @Test
    fun `faster modes are strictly faster than cautious`() {
        assertTrue(DownloadSpeed.FAST.intervalMs < DownloadSpeed.NORMAL.intervalMs)
        assertTrue(DownloadSpeed.NORMAL.intervalMs < DownloadSpeed.CAUTIOUS.intervalMs)
    }

    @Test
    fun `rest counts stay inside each mode range`() {
        for (speed in DownloadSpeed.entries) {
            assertEquals(speed.restAfterMin, speed.requestsUntilRest(0.0))
            assertEquals(speed.restAfterMax, speed.requestsUntilRest(0.9999))
        }
    }

    @Test
    fun `switching the mode takes effect on the next request`() = runTest {
        var now = 0L
        var speed = DownloadSpeed.CAUTIOUS
        val times = ArrayList<Long>()
        val transport = FakeTransport { _ -> times.add(now); page("ok") }
        val throttle = HostThrottle(speed = { speed }, now = { now }, random = { 0.5 }, sleep = { now += it })
        val http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, throttle)
        http.get("https://novel.example/1")
        speed = DownloadSpeed.FAST
        http.get("https://novel.example/2")
        http.get("https://novel.example/3")
        assertEquals(listOf(DownloadSpeed.FAST.intervalMs), times.zipWithNext { a, b -> b - a }.drop(1))
    }

    // ---------- robots.txt ----------

    @Test
    fun `disallow and allow pick the longest pattern`() {
        val robots = RobotsTxt.parse(
            "User-agent: *\nDisallow: /private\nAllow: /private/open\nDisallow: /*.json$"
        )
        assertFalse(robots.isAllowed("/private/page"))
        assertTrue(robots.isAllowed("/private/open/page"))
        assertFalse(robots.isAllowed("/data/list.json"))
        assertTrue(robots.isAllowed("/data/list.json?x=1"))
        assertTrue(robots.isAllowed("/novel/1"))
    }

    @Test
    fun `empty disallow allows everything`() {
        assertTrue(RobotsTxt.parse("User-agent: *\nDisallow:").isAllowed("/anything"))
    }

    @Test
    fun `our own group beats the star group`() {
        val robots = RobotsTxt.parse(
            "User-agent: *\nDisallow: /\nCrawl-delay: 30\n\nUser-agent: LocalDocAI\nDisallow: /admin\nCrawl-delay: 5"
        )
        assertTrue(robots.isAllowed("/novel/1"))
        assertFalse(robots.isAllowed("/admin/x"))
        assertEquals(5_000L, robots.crawlDelayMs)
    }

    @Test
    fun `crawl delay is read as seconds and comments are ignored`() {
        val robots = RobotsTxt.parse("# hello\nUser-agent: *  # all\nCrawl-delay: 2.5\nDisallow: /x # no")
        assertEquals(2_500L, robots.crawlDelayMs)
        assertFalse(robots.isAllowed("/x/1"))
    }

    @Test
    fun `several user agents share one group`() {
        assertFalse(RobotsTxt.parse("User-agent: bot1\nUser-agent: *\nDisallow: /a").isAllowed("/a"))
    }

    @Test
    fun `no rules mean allow all and no delay`() {
        assertTrue(RobotsTxt.parse("").isAllowed("/x"))
        assertNull(RobotsTxt.parse("User-agent: *\nDisallow: /a").crawlDelayMs)
    }

    @Test
    fun `crawl delay from robots stretches the pause and robots is fetched once`() = runTest {
        var now = 0L
        val times = ArrayList<Long>()
        val transport = FakeTransport { req ->
            if (req.url.endsWith("/robots.txt")) page("User-agent: *\nCrawl-delay: 20") else { times.add(now); page("ok") }
        }
        val throttle = HostThrottle(speed = { DownloadSpeed.FAST }, now = { now }, random = { 0.5 }, sleep = { now += it })
        lateinit var http: WebHttp
        http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, throttle, RobotsPolicy({ http.getUnthrottled(it) }, now = { now }))
        repeat(3) { http.get("https://novel.example/ch$it") }
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= 20_000L }, "паузы: $gaps")
        assertEquals(1, transport.requests.count { it.url.endsWith("/robots.txt") })
    }

    @Test
    fun `a missing robots file does not block automatic checks`() = runTest {
        val transport = FakeTransport { req -> if (req.url.endsWith("/robots.txt")) page("nope", status = 404) else page("ok") }
        lateinit var http: WebHttp
        http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, HostThrottle(intervalMs = 0, sleep = {}), RobotsPolicy({ http.getUnthrottled(it) }))
        assertTrue(http.allowedByRobots("https://novel.example/book/1"))
    }

    @Test
    fun `a disallowed path is refused for automatic checks`() = runTest {
        val transport = FakeTransport { req -> if (req.url.endsWith("/robots.txt")) page("User-agent: *\nDisallow: /book/") else page("ok") }
        lateinit var http: WebHttp
        http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, HostThrottle(intervalMs = 0, sleep = {}), RobotsPolicy({ http.getUnthrottled(it) }))
        assertFalse(http.allowedByRobots("https://novel.example/book/1"))
        assertTrue(http.allowedByRobots("https://novel.example/other"))
    }

    @Test
    fun `path and origin extraction`() {
        assertEquals("/a/b?c=d", RobotsPolicy.pathOf("https://x.example/a/b?c=d#frag"))
        assertEquals("/", RobotsPolicy.pathOf("https://x.example"))
        assertEquals("/?q=1", RobotsPolicy.pathOf("https://x.example?q=1"))
        assertEquals("https://x.example:8080", RobotsPolicy.originOf("HTTPS://user@X.example:8080/a"))
    }
}
