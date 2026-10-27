package com.localdoc.ai.rag

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class E5TokenizerTest {

    /** Мини-словарь в формате tokenizer.json (Unigram, как у XLM-R): id = позиция в vocab. */
    private val json = """
        {"version":"1.0","added_tokens":[{"id":0,"content":"<s>"}],
         "model":{"type":"Unigram","unk_id":3,"vocab":[
           ["<s>",0.0],["<pad>",0.0],["</s>",0.0],["<unk>",0.0],
           ["▁",-2.0],["▁hello",-3.0],["▁world",-3.5],["hel",-4.0],["lo",-4.0],
           ["▁hel",-4.5],["▁привет",-5.0],["мир",-6.0],["▁мир",-5.5],
           ["\"",-7.0],["é",-7.5],["\u00e8",-7.5],["-1.5e1",-9.0]
         ]}}
    """.trimIndent()

    private val tokenizer = UnigramTokenizer.fromTokenizerJson(json, maxLength = 16)

    private fun ids(text: String) = tokenizer.encode(text).toList()

    @Test
    fun wrapsTextInClsAndSep() {
        assertEquals(listOf(0L, 5L, 6L, 2L), ids("hello world"))
    }

    @Test
    fun emptyTextIsOnlySpecialTokens() {
        assertEquals(listOf(0L, 2L), ids(""))
        assertEquals(listOf(0L, 2L), ids("   \n\t "))
    }

    @Test
    fun whitespaceRunsAreCollapsed() {
        assertEquals(ids("hello world"), ids("  hello \n\t world  "))
    }

    @Test
    fun viterbiPicksBestSegmentation() {
        // «▁hello» (-3.0) лучше, чем «▁hel»+«lo» (-4.5 + -4.0)
        assertEquals(listOf(0L, 5L, 2L), ids("hello"))
        // «▁мир» (-5.5) лучше, чем «▁»+«мир» (-2.0 + -6.0)
        assertEquals(listOf(0L, 12L, 2L), ids("мир"))
        assertEquals(listOf(0L, 10L, 12L, 2L), ids("привет мир"))
    }

    @Test
    fun unknownCharacterBecomesUnk() {
        // «ж» нет в словаре: слово «▁» + unk
        assertEquals(listOf(0L, 4L, 3L, 2L), ids("ж"))
    }

    @Test
    fun surrogatePairIsOneUnknown() {
        assertEquals(listOf(0L, 4L, 3L, 2L), ids("\uD83D\uDE00"))
    }

    @Test
    fun truncatesKeepingTrailingSep() {
        val short = UnigramTokenizer.fromTokenizerJson(json, maxLength = 4)
        val out = short.encode("hello world hello world hello").toList()
        assertEquals(4, out.size)
        assertEquals(0L, out.first())
        assertEquals(2L, out.last())
    }

    @Test
    fun parsesEscapesInVocab() {
        assertEquals(listOf(0L, 4L, 13L, 2L), ids("\""))
        assertEquals(listOf(0L, 4L, 14L, 2L), ids("\u00e9"))
        assertEquals(listOf(0L, 4L, 15L, 2L), ids("\u00e8"))
    }

    @Test
    fun deterministic() {
        assertContentEquals(tokenizer.encode("hello world"), tokenizer.encode("hello world"))
    }

    @Test
    fun rejectsJsonWithoutVocabOrSpecials() {
        assertFailsWith<IllegalArgumentException> { UnigramTokenizer.fromTokenizerJson("{}") }
        assertFailsWith<IllegalArgumentException> {
            UnigramTokenizer.fromTokenizerJson("""{"model":{"vocab":[["a",-1.0]]}}""")
        }
    }
}
