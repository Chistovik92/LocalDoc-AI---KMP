package com.localdoc.ai.novel

import com.localdoc.ai.TestStrings
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.domain.ChapterSource
import com.localdoc.ai.net.HttpStatusException
import com.localdoc.ai.parser.WebPage
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Запуск по расписанию: что проверяется, что уведомляется и когда назначается следующий запуск. */
class AutoUpdateServiceTest {

    @BeforeTest
    fun strings() = TestStrings.install()

    @AfterTest
    fun resetStrings() {
        com.localdoc.ai.i18n.L10n.testResolver = null
    }

    private val hour = 3_600_000L
    private val monday = 20_458L * 24 * hour

    private class Source(var chapters: Int = 5, var status: Int? = null) : ChapterSource {
        var calls = 0
        override suspend fun load(url: String): WebPage = error("не нужен")
        override suspend fun tableOfContents(tocUrl: String): List<PageLink> {
            calls++
            status?.let { throw HttpStatusException(it, tocUrl) }
            return (1..chapters).map { PageLink("Глава $it", "https://site.example/ch/$it") }
        }
    }

    private class Harness(
        var settings: AutoUpdateSettings = AutoUpdateSettings(enabled = true, quietEnabled = false),
        var network: NetworkKind = NetworkKind.UNMETERED,
        var now: Long = 0L,
        val source: Source = Source()
    ) {
        val tracker = NovelTracker({ null }, { }).apply {
            register("a", "Моя новелла", "https://site.example/toc", (1..3).map { ChapterDiff.normalizeUrl("https://site.example/ch/$it") })
        }
        var lastRun: Long? = null
        val scheduled = ArrayList<Pair<Long, Boolean>>()
        var cancelled = 0
        val notices = ArrayList<Pair<String, String>>()
        val service = AutoUpdateService(
            settings = { settings },
            checker = NovelUpdateChecker(tracker, source, now = { now }),
            lastRun = { lastRun },
            saveLastRun = { lastRun = it },
            network = { network },
            tzOffsetMinutes = { 0 },
            now = { now },
            schedule = { at, unmetered -> scheduled.add(at to unmetered) },
            cancel = { cancelled++ },
            notify = { title, text -> notices.add(title to text) }
        )
    }

    @Test
    fun `a normal run checks, notifies and schedules the next one`() = runTest {
        val h = Harness(now = monday + 12 * hour, settings = AutoUpdateSettings(enabled = true, intervalHours = 6, quietEnabled = false))
        assertEquals(RunResult.DONE, h.service.runScheduled())
        assertEquals(1, h.source.calls)
        assertEquals(1, h.notices.size)
        assertTrue(h.notices.single().second.contains("Моя новелла"))
        assertTrue(h.notices.single().second.contains("+2"))
        assertEquals(h.now, h.lastRun)
        assertEquals(h.now + 6 * hour, h.scheduled.single().first)
    }

    @Test
    fun `nothing new means no notification`() = runTest {
        val h = Harness(now = monday + 12 * hour, source = Source(chapters = 3))
        h.service.runScheduled()
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun `disabled schedule cancels background work`() = runTest {
        val h = Harness(settings = AutoUpdateSettings(enabled = false))
        assertEquals(RunResult.DISABLED, h.service.runScheduled())
        assertEquals(1, h.cancelled)
        assertEquals(0, h.source.calls)
    }

    @Test
    fun `mobile data with wifi only skips the run and retries later`() = runTest {
        val h = Harness(now = monday + 12 * hour, network = NetworkKind.MOBILE)
        assertEquals(RunResult.NOT_ALLOWED, h.service.runScheduled())
        assertEquals(0, h.source.calls)
        assertNull(h.lastRun)
        val (at, unmetered) = h.scheduled.single()
        assertTrue(at >= h.now + AutoUpdateService.RETRY_MS)
        assertTrue(unmetered)
    }

    @Test
    fun `quiet hours skip the run and move it to their end`() = runTest {
        val h = Harness(
            now = monday + 2 * hour,
            settings = AutoUpdateSettings(enabled = true, quietEnabled = true, quietFromMinute = 23 * 60, quietToMinute = 7 * 60)
        )
        assertEquals(RunResult.NOT_ALLOWED, h.service.runScheduled())
        assertEquals(0, h.source.calls)
        assertEquals(monday + 7 * hour, h.scheduled.single().first)
    }

    @Test
    fun `a refusal by the site postpones the next run`() = runTest {
        val h = Harness(now = monday + 12 * hour, source = Source(status = 429))
        assertEquals(RunResult.THROTTLED, h.service.runScheduled())
        assertEquals(h.now + UpdateSchedule.THROTTLE_RETRY_HOURS * hour, h.scheduled.single().first)
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun `reschedule uses the last run and the interval`() {
        val h = Harness(now = monday + 12 * hour, settings = AutoUpdateSettings(enabled = true, intervalHours = 24, quietEnabled = false))
        h.lastRun = monday + 10 * hour
        h.service.reschedule()
        assertEquals(monday + 34 * hour, h.scheduled.single().first)
        assertEquals(true, h.scheduled.single().second)
    }

    @Test
    fun `reschedule with the feature off cancels`() {
        val h = Harness(settings = AutoUpdateSettings(enabled = false))
        h.service.reschedule()
        assertEquals(1, h.cancelled)
        assertTrue(h.scheduled.isEmpty())
    }

    @Test
    fun `the notice lists up to three novels and then only counts them`() {
        fun update(n: Int) = NovelUpdate("$n", "Новелла $n", listOf(PageLink("Глава", "u$n")))
        assertEquals("«Новелла 1» +1", AutoUpdateService.noticeText(listOf(update(1))))
        assertEquals("«Новелла 1» +1, «Новелла 2» +1", AutoUpdateService.noticeText(listOf(update(1), update(2))))
        assertEquals("Новых глав: 4 в 4 новеллах", AutoUpdateService.noticeText((1..4).map(::update)))
    }

    @Test
    fun `found chapters are refreshed before the notification`() = runTest {
        val h = Harness(now = monday + 12 * hour)
        val refreshed = ArrayList<List<NovelUpdate>>()
        val service = AutoUpdateService(
            settings = { h.settings },
            checker = NovelUpdateChecker(h.tracker, h.source, now = { h.now }),
            lastRun = { h.lastRun },
            saveLastRun = { h.lastRun = it },
            network = { h.network },
            tzOffsetMinutes = { 0 },
            now = { h.now },
            schedule = { at, unmetered -> h.scheduled.add(at to unmetered) },
            cancel = { h.cancelled++ },
            notify = { title, text -> h.notices.add(title to text) },
            refresh = { updates ->
                refreshed.add(updates)
                // Одна глава скачалась, другая осталась недоступной
                updates.map { NovelUpdate(it.id, it.title, it.newChapters.take(1)) }
            }
        )
        assertEquals(RunResult.DONE, service.runScheduled())
        assertEquals(1, refreshed.size)
        assertEquals(2, refreshed.single().single().newChapters.size, "в докачку передаются все найденные главы")
        assertEquals(1, h.notices.single().second.count { it == '+' }, "уведомление только о реально скачанных главах")
        assertTrue(h.notices.single().second.contains("+1"))
    }
}
