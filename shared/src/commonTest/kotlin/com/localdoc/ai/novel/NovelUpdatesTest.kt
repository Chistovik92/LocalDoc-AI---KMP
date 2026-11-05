package com.localdoc.ai.novel

import com.localdoc.ai.book.PageLink
import com.localdoc.ai.net.HttpStatusException
import com.localdoc.ai.parser.WebPage
import com.localdoc.ai.domain.ChapterSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Дедупликация глав и проверка новых глав: обычный ход, повторы адресов, отказы сайта (429 / 503), robots.txt. */
class NovelUpdatesTest {

    private fun links(host: String, vararg numbers: Int) = numbers.map { PageLink("Глава $it", "https://$host/ch/$it") }

    // ---------- сравнение списков глав ----------

    @Test
    fun `urls that differ only by noise are the same chapter`() {
        val a = ChapterDiff.normalizeUrl("http://www.Site.example/ch/5/?utm_source=x&fbclid=1#top")
        val b = ChapterDiff.normalizeUrl("https://site.example/ch/5")
        assertEquals(b, a)
    }

    @Test
    fun `meaningful query parameters are kept and sorted`() {
        assertEquals(
            ChapterDiff.normalizeUrl("https://site.example/read?book=1&ch=2"),
            ChapterDiff.normalizeUrl("https://site.example/read?ch=2&book=1")
        )
        assertFalse(ChapterDiff.normalizeUrl("https://site.example/read?ch=2") == ChapterDiff.normalizeUrl("https://site.example/read?ch=3"))
    }

    @Test
    fun `duplicates in a table of contents are dropped keeping the first`() {
        val toc = listOf(
            PageLink("Глава 1", "https://site.example/ch/1"),
            PageLink("Глава 2", "https://site.example/ch/2"),
            PageLink("Последняя глава", "https://site.example/ch/2/?utm_medium=a"),
            PageLink("Глава 3", "https://site.example/ch/3#comments")
        )
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 3"), ChapterDiff.dedupe(toc).map { it.text })
    }

    @Test
    fun `only chapters missing from the known list are new and the order is kept`() {
        val known = (1..5).map { ChapterDiff.normalizeUrl("https://site.example/ch/$it") }
        val diff = ChapterDiff.diff(known, links("site.example", 1, 2, 3, 4, 5, 6, 7))
        assertEquals(listOf("Глава 6", "Глава 7"), diff.newChapters.map { it.text })
        assertFalse(diff.restructured)
    }

    @Test
    fun `a chapter linked twice is counted once`() {
        val known = listOf(ChapterDiff.normalizeUrl("https://site.example/ch/1"))
        val fresh = links("site.example", 1, 2) + PageLink("Новая", "https://site.example/ch/2?utm_source=feed")
        assertEquals(1, ChapterDiff.diff(known, fresh).newChapters.size)
    }

    @Test
    fun `a site that changed all its addresses is not reported as new chapters`() {
        val known = (1..8).map { ChapterDiff.normalizeUrl("https://old.example/ch/$it") }
        val diff = ChapterDiff.diff(known, links("new.example", 1, 2, 3, 4, 5, 6, 7, 8, 9))
        assertTrue(diff.restructured)
        assertTrue(diff.newChapters.isEmpty())
    }

    @Test
    fun `a short or empty fresh list never reports anything`() {
        val known = (1..8).map { ChapterDiff.normalizeUrl("https://site.example/ch/$it") }
        assertTrue(ChapterDiff.diff(known, emptyList()).newChapters.isEmpty())
        assertTrue(ChapterDiff.diff(emptyList(), links("site.example", 1, 2, 3)).newChapters.isEmpty())
    }

    // ---------- проверка по расписанию ----------

    private class FakeToc(private val answers: MutableMap<String, () -> List<PageLink>>) : ChapterSource {
        val requested = ArrayList<String>()
        override suspend fun load(url: String): WebPage = error("не нужен")
        override suspend fun tableOfContents(tocUrl: String): List<PageLink> {
            requested.add(tocUrl)
            return answers.getValue(tocUrl)()
        }
    }

    private fun tracker() = NovelTracker({ null }, { })

    private fun NovelTracker.track(host: String, id: String, upTo: Int, auto: Boolean = true) {
        register(id, "Новелла $id", "https://$host/toc/$id", (1..upTo).map { "https://$host/ch/$it" }.map(ChapterDiff::normalizeUrl))
        if (!auto) setAutoCheck(id, false)
    }

    @Test
    fun `new chapters are found, stored as pending and reported once`() = runTest {
        val tracker = tracker().apply { track("site.example", "a", 3) }
        var chapters = 5
        val source = FakeToc(mutableMapOf("https://site.example/toc/a" to { links("site.example", *(1..chapters).toList().toIntArray()) }))
        val checker = NovelUpdateChecker(tracker, source, now = { 1_000L })

        val first = checker.checkAll()
        assertEquals(1, first.checked)
        assertEquals(listOf(2), first.updates.map { it.newChapters.size })
        assertEquals(2, tracker.all.value.single().pending.size)
        assertEquals(1_000L, tracker.all.value.single().lastCheckedMs)

        val second = checker.checkAll()
        assertTrue(second.updates.isEmpty(), "то же самое второй раз не сообщается")
        assertEquals(2, tracker.all.value.single().pending.size, "ожидающие главы сохраняются до скачивания")

        chapters = 6
        assertEquals(1, checker.checkAll().updates.single().newChapters.size)
        assertEquals(3, tracker.all.value.single().pending.size)
    }

    @Test
    fun `downloading the pending chapters clears them`() = runTest {
        val tracker = tracker().apply { track("site.example", "a", 3) }
        val source = FakeToc(mutableMapOf("https://site.example/toc/a" to { links("site.example", 1, 2, 3, 4) }))
        NovelUpdateChecker(tracker, source).checkAll()
        tracker.markDownloaded("a", (1..4).map { ChapterDiff.normalizeUrl("https://site.example/ch/$it") })
        assertTrue(tracker.all.value.single().pending.isEmpty())
    }

    @Test
    fun `429 stops the whole site but other sites are still checked`() = runTest {
        val tracker = tracker().apply {
            track("slow.example", "a", 2)
            track("slow.example", "b", 2)
            track("fine.example", "c", 2)
        }
        val source = FakeToc(
            mutableMapOf(
                "https://slow.example/toc/a" to { throw HttpStatusException(429, "https://slow.example/toc/a") },
                "https://slow.example/toc/b" to { links("slow.example", 1, 2, 3) },
                "https://fine.example/toc/c" to { links("fine.example", 1, 2, 3) }
            )
        )
        val report = NovelUpdateChecker(tracker, source).checkAll()
        assertEquals(setOf("slow.example"), report.throttledHosts)
        assertTrue(report.throttled)
        assertFalse("https://slow.example/toc/b" in source.requested, "после отказа сайт больше не трогаем")
        assertEquals(listOf("Новелла c"), report.updates.map { it.title })
        assertEquals(1, report.checked)
    }

    @Test
    fun `503 also counts as a refusal`() = runTest {
        val tracker = tracker().apply { track("slow.example", "a", 2); track("slow.example", "b", 2) }
        val source = FakeToc(
            mutableMapOf(
                "https://slow.example/toc/a" to { throw HttpStatusException(503, "https://slow.example/toc/a") },
                "https://slow.example/toc/b" to { links("slow.example", 1, 2, 3) }
            )
        )
        val report = NovelUpdateChecker(tracker, source).checkAll()
        assertTrue(report.throttled)
        assertEquals(1, source.requested.size)
        assertTrue(report.updates.isEmpty())
    }

    @Test
    fun `404 or a network error fails one novel only`() = runTest {
        val tracker = tracker().apply { track("a.example", "a", 2); track("b.example", "b", 2) }
        val source = FakeToc(
            mutableMapOf(
                "https://a.example/toc/a" to { throw HttpStatusException(404, "x") },
                "https://b.example/toc/b" to { links("b.example", 1, 2, 3) }
            )
        )
        val report = NovelUpdateChecker(tracker, source).checkAll()
        assertEquals(1, report.failed)
        assertFalse(report.throttled)
        assertEquals(1, report.updates.size)
        assertTrue(tracker.all.value.first { it.id == "a" }.lastError != null)
        assertEquals(null, tracker.all.value.first { it.id == "b" }.lastError)
    }

    @Test
    fun `sites that forbid automatic access in robots are skipped`() = runTest {
        val tracker = tracker().apply { track("closed.example", "a", 2); track("open.example", "b", 2) }
        val source = FakeToc(
            mutableMapOf(
                "https://closed.example/toc/a" to { links("closed.example", 1, 2, 3) },
                "https://open.example/toc/b" to { links("open.example", 1, 2, 3) }
            )
        )
        val checker = NovelUpdateChecker(tracker, source, allowed = { !it.startsWith("https://closed.example") })
        val report = checker.checkAll()
        assertEquals(listOf("Новелла a"), report.skippedByRobots)
        assertFalse("https://closed.example/toc/a" in source.requested)
        assertEquals(1, report.updates.size)
    }

    @Test
    fun `novels with auto check off are not touched`() = runTest {
        val tracker = tracker().apply { track("site.example", "a", 2, auto = false) }
        val source = FakeToc(mutableMapOf())
        val report = NovelUpdateChecker(tracker, source).checkAll()
        assertEquals(0, report.checked)
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun `a restructured site resets the baseline without a notification`() = runTest {
        val tracker = tracker().apply { track("old.example", "a", 8) }
        val source = FakeToc(mutableMapOf("https://old.example/toc/a" to { links("new.example", 1, 2, 3, 4, 5, 6, 7, 8, 9) }))
        val checker = NovelUpdateChecker(tracker, source)
        assertTrue(checker.checkAll().updates.isEmpty())
        assertTrue(tracker.all.value.single().knownUrls.all { it.contains("new.example") })
        assertTrue(checker.checkAll().updates.isEmpty())
    }

    @Test
    fun `tracked novels survive a restart through storage`() {
        var saved: String? = null
        val first = NovelTracker({ saved }, { saved = it })
        first.register("a", "Новелла", "https://site.example/toc", listOf("https://site.example/ch/1"))
        val second = NovelTracker({ saved }, { saved = it })
        assertEquals("Новелла", second.all.value.single().title)
    }

    @Test
    fun `registering again keeps the user choice and merges known chapters`() {
        val tracker = tracker()
        tracker.register("a", "Новелла", "https://site.example/toc", listOf("https://site.example/ch/1"))
        tracker.setAutoCheck("a", false)
        tracker.register("a", "Новелла", "https://site.example/toc", listOf("https://site.example/ch/1", "https://site.example/ch/2"))
        val novel = tracker.all.value.single()
        assertFalse(novel.autoCheck)
        assertEquals(2, novel.knownUrls.size)
    }
}
