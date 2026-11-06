package com.localdoc.ai.tts

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.BookPager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class PagedFakeEngine(var autoFinish: Boolean = true) : SpeechEngine {
    val spoken = ArrayList<String>()
    private val callbacks = ArrayList<(Boolean) -> Unit>()
    override fun speak(text: String, onFinished: (Boolean) -> Unit) {
        spoken.add(text)
        if (autoFinish) onFinished(true) else callbacks.add(onFinished)
    }
    fun finishNext(success: Boolean = true) {
        check(callbacks.isNotEmpty())
        callbacks.removeAt(0)(success)
    }
    override fun stop() = Unit
    override fun setRate(rate: Float) = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun prepare(onReady: () -> Unit) = onReady()
    override fun voices(): List<Voice> = emptyList()
    override fun setVoice(id: String?) = Unit
    override fun engines(): List<TtsEngineInfo> = emptyList()
    override fun setEngine(id: String?) = Unit
}

/** Источник, который считает, какие страницы реально запрашивались. */
private class CountingSource(private val all: List<Block>, override val pager: BookPager) : SpeechPageSource {
    val requested = ArrayList<Int>()
    override suspend fun blocks(page: Int): List<Block> {
        requested.add(page)
        val p = pager.page(page)
        return all.subList(p.startBlock, p.endBlock)
    }
}

class PagedReadAloudTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun paragraphs(count: Int) = List(count) { Block(BlockType.PARAGRAPH, "Абзац номер ${it + 1} книги читается вслух целиком.") }

    @Test
    fun `reading crosses page boundaries in order until the book ends`() {
        val blocks = paragraphs(30)
        val pager = BookPager.build(blocks, targetChars = 150)
        assertTrue(pager.size >= 8)
        val engine = PagedFakeEngine()
        val controller = ReadAloudController(engine, scope)

        controller.start("book", blocks, emptyList(), 0, pager = pager)

        val reading = engine.spoken.filter { it.startsWith("Абзац") }
        assertEquals(blocks.map { it.text }, reading)
        assertEquals(ReadAloudStop.BOOK_END, controller.state.value.stoppedAt)
    }

    @Test
    fun `only a window of two pages is prepared for a huge book`() {
        val blocks = paragraphs(2_000)
        val pager = BookPager.build(blocks, targetChars = 600)
        val source = CountingSource(blocks, pager)
        val engine = PagedFakeEngine(autoFinish = false)
        val controller = ReadAloudController(engine, scope)

        controller.start("book", source, emptyList(), 0)

        assertTrue(pager.size > 100)
        assertEquals(listOf(0, 1), source.requested.distinct(), "в работе только текущая и следующая страницы")
        assertEquals(0, controller.state.value.page)
        assertEquals(pager.size, controller.state.value.pageCount)
    }

    @Test
    fun `start from the middle of a book opens the page of that block`() {
        val blocks = paragraphs(200)
        val pager = BookPager.build(blocks, targetChars = 600)
        val source = CountingSource(blocks, pager)
        val engine = PagedFakeEngine(autoFinish = false)
        val controller = ReadAloudController(engine, scope)

        controller.start("book", source, emptyList(), 120)

        assertEquals(120, controller.state.value.segment?.block)
        assertEquals(pager.pageOfBlock(120), controller.state.value.page)
        assertTrue(source.requested.all { it >= pager.pageOfBlock(120) })
    }

    @Test
    fun `previous sentence at the start of a page goes to the end of the previous one`() {
        val blocks = paragraphs(30)
        val pager = BookPager.build(blocks, targetChars = 150)
        val engine = PagedFakeEngine(autoFinish = false)
        val controller = ReadAloudController(engine, scope)
        val secondPageStart = pager.page(1).startBlock

        controller.start("book", blocks, emptyList(), secondPageStart, pager = pager)
        assertEquals(1, controller.state.value.page)
        controller.previousSentence()

        assertEquals(0, controller.state.value.page)
        assertEquals(secondPageStart - 1, controller.state.value.segment?.block)
    }

    @Test
    fun `jump to a far block reopens the window there`() {
        val blocks = paragraphs(100)
        val pager = BookPager.build(blocks, targetChars = 300)
        val engine = PagedFakeEngine(autoFinish = false)
        val controller = ReadAloudController(engine, scope)

        controller.start("book", blocks, emptyList(), 0, pager = pager)
        controller.jumpToBlock(90)

        assertEquals(90, controller.state.value.segment?.block)
        assertEquals(pager.pageOfBlock(90), controller.state.value.page)
        assertTrue(controller.state.value.playing)
    }

    private inner class WaitingPageHarness {
        val blocks = paragraphs(6).map { Block(it.type, it.text.repeat(4)) }
        val pager = BookPager.build(blocks, targetChars = 200)
        val engine = PagedFakeEngine(autoFinish = false)
        var releasePage: (() -> Unit)? = null
        val source = object : SpeechPageSource {
            override val pager = this@WaitingPageHarness.pager
            override suspend fun blocks(page: Int): List<Block> {
                if (page == 2) kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    releasePage = { continuation.resumeWith(Result.success(Unit)) }
                }
                val p = pager.page(page)
                return blocks.subList(p.startBlock, p.endBlock)
            }
        }
        val controller = ReadAloudController(engine, scope)

        init {
            controller.start("book", source, emptyList(), 0)
            engine.finishNext()
            engine.finishNext()
            assertNotNull(releasePage, "третья страница должна ожидать подготовки")
        }
    }

    @Test
    fun `pause cancels pending page advance`() {
        val h = WaitingPageHarness()

        h.controller.pause()
        h.releasePage?.invoke()

        assertFalse(h.controller.state.value.playing)
        assertEquals(2, h.engine.spoken.size, "после паузы следующая страница не запускается сама")

        h.controller.play()
        assertTrue(h.controller.state.value.playing)
        assertEquals(3, h.engine.spoken.size)
    }

    @Test
    fun `seek cancels pending page advance`() {
        val h = WaitingPageHarness()

        h.controller.pause()
        h.controller.nextSentence()
        h.releasePage?.invoke()

        assertEquals(2, h.engine.spoken.size)
        assertEquals(1, h.controller.state.value.segment?.block)
        h.controller.play()
        assertEquals(3, h.engine.spoken.size)
    }

    @Test
    fun `page load completion resumes pending playback`() {
        val h = WaitingPageHarness()

        h.releasePage?.invoke()

        assertTrue(h.controller.state.value.playing)
        assertEquals(3, h.engine.spoken.size)
        assertEquals(2, h.controller.state.value.segment?.block)
    }

    @Test
    fun `code only pages are skipped and a broken source does not crash`() {
        val blocks = listOf(
            Block(BlockType.PARAGRAPH, "Первый абзац перед кодом, достаточно длинный."),
            Block(BlockType.CODE, "x".repeat(900)),
            Block(BlockType.PARAGRAPH, "Последний абзац после кода, тоже длинный.")
        )
        val pager = BookPager.build(blocks, targetChars = 300)
        val engine = PagedFakeEngine()
        val controller = ReadAloudController(engine, scope)
        controller.start("book", blocks, emptyList(), 0, pager = pager)
        assertTrue(engine.spoken.any { it.startsWith("Последний") })
        assertFalse(engine.spoken.any { it.startsWith("xxx") })

        val failing = object : SpeechPageSource {
            override val pager = BookPager.build(blocks)
            override suspend fun blocks(page: Int): List<Block> = throw IllegalStateException("нет модели перевода")
        }
        controller.start("other", failing, emptyList(), 0)
        assertEquals("В тексте нет фрагментов для озвучки", controller.state.value.error)
    }
}
