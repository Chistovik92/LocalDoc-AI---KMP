package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.domain.TranslationSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Озвучка перевода по мере готовности: первый готовый блок звучит переводом, остальные —
 * оригиналом; текст страницы не теряется, число блоков не меняется.
 */
class StreamingPageTranslationTest {

    private val settings = TranslationSettings(speakAsTranslated = true)
    private val original = listOf(
        Block(BlockType.PARAGRAPH, "first"),
        Block(BlockType.PARAGRAPH, "second"),
        Block(BlockType.PARAGRAPH, "third")
    )

    @Test
    fun `first ready blocks sound translated, the rest keep the original`() = runTest {
        // Переводчик уже выдал первый блок; второй и третий ещё в работе
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, _ -> error("не должен ждать перевода всей страницы") },
            progress = { listOf(Block(BlockType.PARAGRAPH, "первый"), null, null) }
        )
        assertEquals(3, result.size, "число блоков страницы не меняется")
        assertEquals("первый", result[0].text)
        assertEquals("second", result[1].text, "неготовые блоки звучат оригиналом")
        assertEquals("third", result[2].text)
    }

    @Test
    fun `no ready blocks within the timeout falls back to the whole-page translation`() = runTest {
        var wholePageCalled = false
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, blocks ->
                wholePageCalled = true
                blocks.map { it.copy(text = "перевод:${it.text}") }
            },
            progress = { listOf(null, null, null) }
        )
        assertTrue(wholePageCalled, "без готовых блоков ждётся перевод всей страницы")
        assertEquals("перевод:first", result[0].text)
        assertEquals("перевод:third", result[2].text)
    }

    @Test
    fun `finished page sounds as one whole translation`() = runTest {
        val done = listOf(
            Block(BlockType.PARAGRAPH, "первый"),
            Block(BlockType.PARAGRAPH, "второй"),
            Block(BlockType.PARAGRAPH, "третий")
        )
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, _ -> error("страница уже переведена") },
            progress = { done }
        )
        assertEquals("третий", result[2].text)
    }

    @Test
    fun `disabled streaming waits for the whole page`() = runTest {
        val off = TranslationSettings(speakAsTranslated = false)
        val result = StreamingPageTranslation.translate(
            original, key(), off,
            translate = { _, blocks -> blocks.map { it.copy(text = "целиком:${it.text}") } },
            progress = { error("прогресс не спрашивается") }
        )
        assertEquals("целиком:first", result[0].text)
    }

    @Test
    fun `shorter progress than the page is not an error`() = runTest {
        // Переводчик мог потерять страницу (retainOnly) — прогресс короче оригинала
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, _ -> error("не должен вызываться: есть готовый блок") },
            progress = { listOf(Block(BlockType.PARAGRAPH, "первый")) }
        )
        assertEquals(3, result.size)
        assertEquals("second", result[1].text)
    }

    private fun key() = PageKey("doc", 0, "ru", null, 1000)
}
