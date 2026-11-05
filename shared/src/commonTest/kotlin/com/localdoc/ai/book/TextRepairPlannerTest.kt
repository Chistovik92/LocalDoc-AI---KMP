package com.localdoc.ai.book

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextRepairPlannerTest {
    private fun p(text: String) = Block(BlockType.PARAGRAPH, text)

    private class FakeModel(private val answer: (String) -> String) {
        val prompts = ArrayList<String>()
        val model = ChapterModel { _, user, _ -> prompts.add(user); answer(user) }
    }

    /** Пары «длинная строка без точки» + «строка с имени»: программные правила их не склеивают, модель решает. */
    private fun ragged(pairs: Int) = (0 until pairs).flatMap { i ->
        listOf(p("Вчера вечером я долго разговаривал по телефону с моим старым другом по имени ${"абвгдежзик"[i % 10]}ван"), p("Иваном о том, что случилось на прошлой неделе."))
    }

    @Test
    fun `model confirms a broken sentence and the lines are joined`() = runTest {
        val blocks = listOf(p("Вчера вечером я долго разговаривал с моим старым другом"), p("Иваном о том, что произошло."), p("Новый абзац."))
        val result = TextRepairPlanner(FakeModel { "1" }.model).plan(blocks)
        assertEquals(
            listOf("Вчера вечером я долго разговаривал с моим старым другом Иваном о том, что произошло.", "Новый абзац."),
            result.blocks.map { it.text }
        )
        assertEquals(0, result.newIndex(1))
        assertEquals(1, result.newIndex(2))
    }

    @Test
    fun `model says there is no break and text stays`() = runTest {
        val blocks = ragged(1)
        val result = TextRepairPlanner(FakeModel { "0" }.model).plan(blocks)
        assertEquals(blocks, result.blocks)
    }

    @Test
    fun `without a working model only the programmatic rules apply`() = runTest {
        val blocks = ragged(2) + listOf(p("Он пошёл в"), p("магазин."))
        val failing = ChapterModel { _, _, _ -> error("модель недоступна") }
        val result = TextRepairPlanner(failing).plan(blocks)
        assertEquals(TextRepair.repair(blocks).blocks, result.blocks)
    }

    @Test
    fun `answers outside the window are ignored`() = runTest {
        val blocks = ragged(1)
        val result = TextRepairPlanner(FakeModel { "7, 99" }.model).plan(blocks)
        assertEquals(blocks, result.blocks)
    }

    @Test
    fun `questions are short and asked in windows`() = runTest {
        val blocks = ragged(10)
        val fake = FakeModel { "0" }
        TextRepairPlanner(fake.model, TextRepairPlanner.Settings(windowSize = 4)).plan(blocks)
        assertEquals(3, fake.prompts.size)
        assertTrue(fake.prompts.all { it.length < 1_500 })
    }

    @Test
    fun `nothing suspicious means no questions`() = runTest {
        val fake = FakeModel { "1" }
        TextRepairPlanner(fake.model).plan(listOf(p("Первый абзац."), p("Второй абзац.")))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test
    fun `a glued giant paragraph is split where the model points`() = runTest {
        val sentences = (1..12).map { "Это предложение номер $it, и в нём достаточно слов, чтобы абзац стал очень длинным " + "слово ".repeat(25) + "конец." }
        val giant = sentences.joinToString(" ")
        val fake = FakeModel { "4" }
        val result = TextRepairPlanner(fake.model, TextRepairPlanner.Settings(candidates = 8)).plan(listOf(giant, "Хвост.").map(::p))
        assertTrue(result.blocks.size >= 3)
        assertEquals("Хвост.", result.blocks.last().text)
        assertEquals(giant, result.blocks.dropLast(1).joinToString(" ") { it.text })
        assertTrue(fake.prompts.isNotEmpty())
    }

    @Test
    fun `giant paragraph stays whole when the model does not answer`() = runTest {
        val giant = (1..12).joinToString(" ") { "Предложение номер $it, и в нём достаточно слов " + "слово ".repeat(25) + "конец." }
        val result = TextRepairPlanner(FakeModel { "не знаю" }.model).plan(listOf(p(giant)))
        assertEquals(listOf(giant), result.blocks.map { it.text })
    }
}
