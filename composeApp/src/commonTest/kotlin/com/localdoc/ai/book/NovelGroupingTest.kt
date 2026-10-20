package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NovelGroupingTest {

    private fun web(id: String, title: String, host: String = "ranobe-novels.ru") = BookEntry(
        id = id,
        title = title,
        format = BookFormat.WEB,
        source = "https://$host/novel/$id",
        totalBlocks = 10
    )

    @Test
    fun `chapter title yields novel title`() {
        val entry = web("a", "У Меня каждую Неделю новая Профессия 16. Глава 16: Начало")

        assertEquals("У Меня каждую Неделю новая Профессия", entry.novelTitle())
    }

    @Test
    fun `chapter label drops novel title and duplicate number`() {
        val entry = web("a", "Новелла 16. Глава 16: Начало")

        assertEquals("Глава 16: Начало", entry.chapterLabel())
    }

    @Test
    fun `english chapter marker is recognised`() {
        assertEquals("Solo Leveling", web("a", "Solo Leveling - Chapter 12").novelTitle())
    }

    @Test
    fun `title without chapter marker is not a novel chapter`() {
        assertNull(web("a", "Война и мир").novelTitle())
    }

    @Test
    fun `bare chapter title is not groupable`() {
        assertNull(web("a", "Глава 5").novelTitle())
    }

    @Test
    fun `non web entries are never grouped`() {
        val pdf = web("a", "Отчёт, глава 3").copy(format = BookFormat.PDF)

        assertNull(pdf.novelTitle())
    }

    @Test
    fun `two chapters of one novel collapse into one group sorted by number`() {
        val entries = listOf(
            web("c16", "Новелла 16. Глава 16"),
            web("other", "Статья о котах"),
            web("c15", "Новелла 15. Глава 15"),
            web("c2", "Новелла 2. Глава 2")
        )

        val shelf = groupLibrary(entries)

        assertEquals(2, shelf.size)
        val novel = assertIs<LibraryItem.Novel>(shelf[0])
        assertEquals("Новелла", novel.title)
        assertEquals(listOf("c2", "c15", "c16"), novel.chapters.map { it.id })
        assertEquals("c16", novel.mostRecent.id)
        assertIs<LibraryItem.Single>(shelf[1])
    }

    @Test
    fun `single chapter stays a plain book`() {
        val shelf = groupLibrary(listOf(web("c1", "Новелла 1. Глава 1")))

        assertIs<LibraryItem.Single>(shelf.single())
    }

    @Test
    fun `same title on different hosts is not merged`() {
        val shelf = groupLibrary(
            listOf(web("a", "Новелла 1. Глава 1", "one.example"), web("b", "Новелла 2. Глава 2", "two.example"))
        )

        assertEquals(2, shelf.size)
    }
}
