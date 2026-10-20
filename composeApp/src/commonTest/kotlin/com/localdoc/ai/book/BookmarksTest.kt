package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarksTest {

    private var stored: String? = null
    private fun repo() = BookmarkRepository({ stored }, { stored = it })
    private val book = BookEntry("b1", "Война и мир", "Толстой", BookFormat.EPUB, "uri", 500)
    private val web = BookEntry("w1", "Глава 12", "", BookFormat.WEB, "https://novel.example/12", 40)

    @Test
    fun `bookmark is added once per block and survives reload`() {
        val r = repo()
        val first = r.add(book, 10, "Все счастливые семьи похожи друг на друга.", "Часть 1")
        val again = r.add(book, 10, "другой текст")

        assertEquals(first.id, again.id)
        assertEquals(1, r.all.value.size)
        assertEquals("Часть 1", r.all.value.single().chapter)

        val reloaded = repo()
        assertEquals(listOf(first), reloaded.all.value)
    }

    @Test
    fun `toggle on a page sets and then removes the bookmark`() {
        val r = repo()
        assertTrue(r.toggle(book, 20..29, 22, "Текст страницы"))
        assertNotNull(r.findIn("b1", 20..29))
        assertFalse(r.toggle(book, 20..29, 25, "Текст страницы"))
        assertNull(r.findIn("b1", 20..29))
        assertTrue(r.all.value.isEmpty())
    }

    @Test
    fun `entry bookmarks are sorted by position and web bookmarks keep the url`() {
        val r = repo()
        r.add(book, 300, "поздняя")
        r.add(book, 5, "ранняя")
        r.add(web, 3, "веб")

        assertEquals(listOf(5, 300), r.forEntry("b1").map { it.block })
        assertEquals("https://novel.example/12", r.forEntry("w1").single().url)
        assertEquals("", r.forEntry("b1").first().url)
    }

    @Test
    fun `removing a book removes its bookmarks and excerpts are short`() {
        val r = repo()
        r.add(book, 1, "a".repeat(500))
        r.add(web, 2, "b")
        assertTrue(r.all.value.first { it.entryId == "b1" }.excerpt.length <= 91)

        r.removeForEntry("b1")
        assertEquals(listOf("w1"), r.all.value.map { it.entryId })
    }

    @Test
    fun `damaged storage gives an empty list instead of a crash`() {
        stored = "{не json"
        assertTrue(repo().all.value.isEmpty())
    }
}
