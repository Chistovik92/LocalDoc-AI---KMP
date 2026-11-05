package com.localdoc.ai.book

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterPlannerTest {

    private val settings = ChapterPlanner.Settings(minChars = 5_000, maxChars = 12_000, candidates = 8)

    /** Модель по сценарию: [boundary] отвечает на вопрос о границе, [title] — на просьбу назвать главу. */
    private class FakeModel(
        private val boundary: (String) -> String = { "3" },
        private val title: (String) -> String = { "Название" }
    ) {
        val prompts = ArrayList<String>()
        val model = ChapterModel { _, user, _ ->
            prompts.add(user)
            if ("chapter title" in user) title(user) else boundary(user)
        }
    }

    private fun paragraph(n: Int, chars: Int = 1_000) = Block(BlockType.PARAGRAPH, "Абзац $n " + "слово ".repeat(chars / 6))

    private fun book(count: Int, sceneBreakAfter: Set<Int> = emptySet()): List<Block> = buildList {
        for (i in 0 until count) {
            add(paragraph(i))
            if (i in sceneBreakAfter) add(Block(BlockType.PARAGRAPH, "* * *"))
        }
    }

    private fun chars(blocks: List<Block>, from: Int, to: Int) = blocks.subList(from, to).sumOf { it.text.length }

    @Test
    fun `splits a long book into chapters no longer than the limit`() = runTest {
        val blocks = book(40)
        val chapters = ChapterPlanner(FakeModel().model, settings).plan(blocks)
        assertEquals(0, chapters.first().startBlock)
        assertTrue(chapters.size >= 3, "глав: ${chapters.size}")
        val starts = chapters.map { it.startBlock } + blocks.size
        for ((a, b) in starts.zipWithNext()) assertTrue(chars(blocks, a, b) <= settings.maxChars, "глава $a..$b слишком длинная")
        assertEquals(chapters.map { it.startBlock }.sorted(), chapters.map { it.startBlock })
        assertEquals("Глава 1", chapters.first().title)
    }

    @Test
    fun `chapter starts where the model points`() = runTest {
        val blocks = book(20)
        val fake = FakeModel(boundary = { "2" })
        val chapters = ChapterPlanner(fake.model, settings).plan(blocks)
        // окно начинается после minChars (5 абзацев) → кандидаты идут подряд с шестого абзаца, ответ «2» — седьмой
        assertEquals(listOf(0, 6), chapters.map { it.startBlock }.take(2))
    }

    @Test
    fun `falls back to a scene break when the answer is not a number`() = runTest {
        val blocks = book(20, sceneBreakAfter = setOf(7))
        val chapters = ChapterPlanner(FakeModel(boundary = { "не знаю" }).model, settings).plan(blocks)
        // «* * *» стоит после абзаца 7 (индекс блока 8), глава начинается со следующего блока
        assertEquals(9, chapters[1].startBlock)
    }

    @Test
    fun `scene breaks are offered to the model instead of every paragraph`() = runTest {
        val blocks = book(20, sceneBreakAfter = setOf(7))
        val fake = FakeModel()
        ChapterPlanner(fake.model, settings).plan(blocks)
        assertTrue("Абзац 8" in fake.prompts.first(), "после разрыва сцены идёт абзац 8")
        assertTrue("Абзац 6" !in fake.prompts.first(), "остальные абзацы не предлагаются")
    }

    @Test
    fun `short books and books with short chapters are left alone`() = runTest {
        val fake = FakeModel()
        val short = book(6)
        assertEquals(emptyList(), ChapterPlanner(fake.model, settings).plan(short))
        val existing = listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 3))
        assertEquals(existing, ChapterPlanner(fake.model, settings).plan(short, existing))
        assertTrue(fake.prompts.isEmpty(), "модель не вызывалась")
    }

    @Test
    fun `oversized spans between existing chapters are split and existing ones are kept`() = runTest {
        val blocks = book(40)
        val existing = listOf(Chapter("Часть первая", 0), Chapter("Часть вторая", 20))
        val chapters = ChapterPlanner(FakeModel().model, settings).plan(blocks, existing)
        val starts = chapters.map { it.startBlock }
        assertTrue(0 in starts && 20 in starts)
        assertTrue(chapters.size > existing.size)
        assertEquals("Часть первая", chapters.first { it.startBlock == 0 }.title)
        assertEquals("Часть вторая", chapters.first { it.startBlock == 20 }.title)
        assertTrue(chapters.filter { it.startBlock !in setOf(0, 20) }.all { it.title.startsWith("Глава ") })
    }

    @Test
    fun `model titles are cleaned and appended to the number`() = runTest {
        val blocks = book(20)
        val fake = FakeModel(title = { "\"Тихая гавань.\"\nещё строка" })
        val chapters = ChapterPlanner(fake.model, settings.copy(titles = true)).plan(blocks)
        assertEquals("Глава 1. Тихая гавань", chapters.first().title)
    }

    @Test
    fun `progress ends at one`() = runTest {
        val seen = ArrayList<Float>()
        ChapterPlanner(FakeModel().model, settings).plan(book(40)) { seen.add(it) }
        assertEquals(1f, seen.last())
        assertEquals(seen.sorted(), seen)
    }
}
