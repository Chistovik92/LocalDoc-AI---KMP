package com.localdoc.ai.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextChunkerTest {

    @Test
    fun emptyTextProducesNoChunks() {
        assertTrue(TextChunker.chunk("   \n\t ").isEmpty())
    }

    @Test
    fun shortTextIsSingleNormalizedChunk() {
        assertEquals(listOf("один два три"), TextChunker.chunk("один\n два   три"))
    }

    @Test
    fun longTextIsSplitWithOverlap() {
        val words = (1..25).map { "w$it" }
        val chunks = TextChunker.chunk(words.joinToString(" "), chunkSizeWords = 10, overlapWords = 3)

        // Шаг окна = 10 - 3 = 7: чанки начинаются со слов 0, 7, 14, 21
        assertEquals(4, chunks.size)
        assertEquals(words.subList(0, 10).joinToString(" "), chunks[0])
        // Следующий чанк начинается за 3 слова до конца предыдущего
        assertEquals(words.subList(7, 17).joinToString(" "), chunks[1])
        // Последний чанк заканчивается последним словом текста
        assertTrue(chunks.last().endsWith("w25"))
    }

    @Test
    fun overlapNotSmallerThanChunkIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            TextChunker.chunk("a b c", chunkSizeWords = 3, overlapWords = 3)
        }
    }
}
