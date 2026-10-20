package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageDetectorTest {

    @Test
    fun `scripts are recognised`() {
        assertEquals("ru", LanguageDetector.detect("Это обычное русское предложение для проверки."))
        assertEquals("uk", LanguageDetector.detect("Це звичайне українське речення, яке ми перевіряємо."))
        assertEquals("ja", LanguageDetector.detect("これは日本語の文章です。テストのために書かれています。"))
        assertEquals("zh", LanguageDetector.detect("这是一个用于测试的中文句子，没有假名。"))
        assertEquals("ko", LanguageDetector.detect("이것은 테스트를 위한 한국어 문장입니다."))
        assertEquals("ar", LanguageDetector.detect("هذه جملة عربية بسيطة للاختبار الآن"))
        assertEquals("el", LanguageDetector.detect("Αυτή είναι μια απλή ελληνική πρόταση για δοκιμή."))
    }

    @Test
    fun `latin languages are told apart by common words`() {
        assertEquals("en", LanguageDetector.detect("The old man was sitting in the garden and he did not say a word to them."))
        assertEquals("de", LanguageDetector.detect("Der alte Mann sitzt im Garten und er sagt nicht ein Wort zu den Kindern."))
        assertEquals("fr", LanguageDetector.detect("Le vieil homme est dans le jardin et il ne dit pas un mot pour les enfants."))
        assertEquals("es", LanguageDetector.detect("El viejo está en el jardín y no dice una palabra a los niños, como siempre."))
        assertEquals("pl", LanguageDetector.detect("Stary człowiek siedzi w ogrodzie i nie mówi ani słowa do dzieci, tak jak zawsze."))
    }

    @Test
    fun `short or empty text gives no answer`() {
        assertNull(LanguageDetector.detect(""))
        assertNull(LanguageDetector.detect("Да"))
        assertNull(LanguageDetector.detect("12345 67890 !!!"))
    }

    @Test
    fun `language lookup works for locale tags`() {
        assertEquals("pt", Languages.byCode("pt-BR")?.code)
        assertEquals("en", Languages.byCode("en_GB")?.code)
        assertTrue(Languages.sameLanguage("ru-RU", "ru"))
        assertNull(Languages.byCode("xx"))
        assertTrue(Languages.all.map { it.code }.toSet().size == Languages.all.size)
    }
}

class PageTranslatorTest {

    private val ru = Languages.byCode("ru")!!
    private var calls = 0

    private fun fake(): (String, Language, Language?) -> Flow<String> = { text, _, _ ->
        calls++
        flow { emit("[${text.length}]"); emit("EN:" + text.take(6)) }
    }

    private fun page(vararg texts: String) = texts.map { Block(BlockType.PARAGRAPH, it) }
    private val englishText = "The old man was sitting in the garden and he did not say a word to them."
    private val key = PageKey("b", 0, "ru", null)

    @Test
    fun `blocks are translated keeping types and code stays untouched`() = runTest {
        val translator = PageTranslator(fake(), backgroundScope)
        val blocks = listOf(
            Block(BlockType.HEADING, "Chapter One of the long story", 1),
            Block(BlockType.PARAGRAPH, englishText),
            Block(BlockType.CODE, "val x = 1")
        )

        val result = translator.translate(key, blocks, ru, null)

        assertEquals(listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.CODE), result.map { it.type })
        assertEquals(1, result[0].level)
        assertTrue(result[1].text.startsWith("[") && "EN:" in result[1].text)
        assertEquals("val x = 1", result[2].text)
        assertEquals(2, calls)
    }

    @Test
    fun `finished pages come from cache without calling the model again`() = runTest {
        val translator = PageTranslator(fake(), backgroundScope)
        val blocks = page(englishText)
        translator.translate(key, blocks, ru, null)
        translator.translate(key, blocks, ru, null)

        assertEquals(1, calls)
        assertNotNull(translator.cached(key))
    }

    @Test
    fun `text already in the target language is not translated`() = runTest {
        val translator = PageTranslator(fake(), backgroundScope)
        val russian = page("Это обычное русское предложение, которое не нужно переводить на русский.")
        val result = translator.translate(key, russian, ru, null)

        assertEquals(russian, result)
        assertEquals(0, calls)
    }

    @Test
    fun `long paragraph is cut into sentence chunks`() {
        val sentence = "Это довольно длинное предложение номер один. "
        val chunks = PageTranslator.splitForTranslation(sentence.repeat(60), 500)

        assertTrue(chunks.size > 4)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(sentence.repeat(60).replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertEquals(listOf("коротко"), PageTranslator.splitForTranslation("коротко", 500))
    }

    @Test
    fun `model failure is reported and does not poison the cache`() = runTest {
        var fail = true
        val translator = PageTranslator(
            { text, _, _ -> flow { if (fail) throw IllegalStateException("Модель не загружена"); emit("ok $text") } },
            backgroundScope
        )

        assertFailsWith<IllegalStateException> { translator.translate(key, page(englishText), ru, null) }
        assertTrue(translator.error.value.orEmpty().contains("Модель не загружена"))
        assertNull(translator.cached(key))

        fail = false
        assertEquals("ok $englishText", translator.translate(key, page(englishText), ru, null).single().text)
    }

    @Test
    fun `thinking blocks are stripped and empty answers fall back to the original`() = runTest {
        val outputs = ArrayDeque(listOf("<think>размышляю</think>Привет, мир", "   "))
        val translator = PageTranslator({ _, _, _ -> flow { emit(outputs.removeFirst()) } }, backgroundScope)
        val result = translator.translate(
            key,
            page("Hello world, this is the first block of text for you.", "Second block of english text goes here, friend."),
            ru,
            null
        )

        assertEquals("Привет, мир", result[0].text)
        assertEquals("Second block of english text goes here, friend.", result[1].text)
    }

    @Test
    fun `retainOnly cancels pages that are no longer needed`() = runTest {
        val translator = PageTranslator({ text, _, _ -> flow { kotlinx.coroutines.delay(1_000); emit(text) } }, backgroundScope)
        val far = PageKey("b", 50, "ru", null)
        val job = translator.request(far, page(englishText), ru, null)

        translator.retainOnly(emptySet())
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertNull(translator.cached(far))
    }

    @Test
    fun `hold pauses the queue until released`() = runTest {
        val translator = PageTranslator(fake(), backgroundScope)
        translator.hold()
        val job = translator.request(key, page(englishText), ru, null)
        runCurrent()
        assertEquals(0, calls)

        translator.release()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(job.isCompleted)
    }
}
