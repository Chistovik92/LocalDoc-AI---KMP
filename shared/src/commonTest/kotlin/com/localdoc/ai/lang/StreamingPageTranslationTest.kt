package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.domain.TranslationSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Озвучка перевода: страница отдаётся плееру только после полного перевода, без вставок оригинала. */
class StreamingPageTranslationTest {

    private val settings = TranslationSettings(speakAsTranslated = true)
    private val original = listOf(
        Block(BlockType.PARAGRAPH, "first"),
        Block(BlockType.PARAGRAPH, "second"),
        Block(BlockType.PARAGRAPH, "third")
    )

    @Test
    fun `partial progress never mixes original blocks into speech`() = runTest {
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, blocks -> blocks.map { it.copy(text = "перевод:${it.text}") } },
            progress = { listOf(Block(BlockType.PARAGRAPH, "первый"), null, null) }
        )
        assertEquals(listOf("перевод:first", "перевод:second", "перевод:third"), result.map { it.text })
    }

    @Test
    fun `no ready blocks waits for the whole-page translation`() = runTest {
        var wholePageCalled = false
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, blocks ->
                wholePageCalled = true
                blocks.map { it.copy(text = "перевод:${it.text}") }
            },
            progress = { listOf(null, null, null) }
        )
        assertTrue(wholePageCalled)
        assertEquals("перевод:first", result[0].text)
        assertEquals("перевод:third", result[2].text)
    }

    @Test
    fun `finished page sounds as one whole translation`() = runTest {
        val done = original.map { it.copy(text = "перевод:${it.text}") }
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, _ -> done },
            progress = { done }
        )
        assertEquals("перевод:third", result[2].text)
    }

    @Test
    fun `disabled streaming also waits for the whole page`() = runTest {
        val off = TranslationSettings(speakAsTranslated = false)
        val result = StreamingPageTranslation.translate(
            original, key(), off,
            translate = { _, blocks -> blocks.map { it.copy(text = "целиком:${it.text}") } },
            progress = { error("прогресс не спрашивается") }
        )
        assertEquals("целиком:first", result[0].text)
    }

    @Test
    fun `progress is never consulted before speaking`() = runTest {
        val result = StreamingPageTranslation.translate(
            original, key(), settings,
            translate = { _, blocks -> blocks.map { it.copy(text = "перевод:${it.text}") } },
            progress = { error("частичный снимок не должен попадать в озвучку") }
        )
        assertEquals(3, result.size)
        assertEquals("перевод:second", result[1].text)
    }

    private fun key() = PageKey("doc", 0, "ru", null, 1000)
}
