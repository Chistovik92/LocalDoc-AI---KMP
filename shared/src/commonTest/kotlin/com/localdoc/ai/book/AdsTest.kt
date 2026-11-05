package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Вся реклама убирается из любых источников: импорт, EPUB, TXT, скачанные главы. */
class AdsTest {

    @Test
    fun `promo lines of book sites are recognised but story text is not`() {
        val ads = listOf(
            "Реклама. ООО «Ромашка», erid: 2VtzqwP",
            "Скачано с сайта example.ru",
            "Читайте продолжение на нашем сайте",
            "Присоединяйтесь к нашей группе ВКонтакте!",
            "Подписывайтесь на канал в Telegram",
            "www.fanfics.example",
            "Поддержать автора: boosty.to/author",
            "Все права защищены.",
            "Перевод выполнен командой Лунные Тени"
        )
        ads.forEach { assertTrue(AdFilter.isAdText(it), it) }
        val story = listOf(
            "Присоединяйтесь к нам, сказал он и открыл дверь.",
            "Он прочитал письмо и подписал его.",
            "Поддержка пришла неожиданно.",
            "Вечером они читали продолжение письма."
        )
        story.forEach { assertFalse(AdFilter.isAdText(it), it) }
    }

    @Test
    fun `books lose ad blocks but keep headings and chapter indices`() {
        val book = Book(
            title = "t", format = BookFormat.TEXT,
            blocks = listOf(
                Block(BlockType.HEADING, "Глава 1", 1),
                Block(BlockType.PARAGRAPH, "Текст главы."),
                Block(BlockType.PARAGRAPH, "Скачано с сайта example.ru"),
                Block(BlockType.HEADING, "Глава 2", 1),
                Block(BlockType.PARAGRAPH, "Подписывайтесь на канал в Telegram"),
                Block(BlockType.PARAGRAPH, "Вторая глава.")
            ),
            chapters = listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 3))
        )
        val clean = book.withoutAds()
        assertEquals(listOf("Глава 1", "Текст главы.", "Глава 2", "Вторая глава."), clean.blocks.map { it.text })
        assertEquals(listOf(0, 2), clean.chapters.map { it.startBlock })
    }

    @Test
    fun `import removes ads from plain text and markdown files`() {
        val txt = "Первый абзац истории.\n\nСкачано с сайта example.ru\n\nВторой абзац."
        val book = BookImporter.parseBytes(txt.encodeToByteArray(), BookFormat.TEXT, "t", "u")
        assertEquals(listOf("Первый абзац истории.", "Второй абзац."), book.blocks.map { it.text })
    }

    @Test
    fun `ad containers and banner images are dropped from web pages`() {
        val html = """<html><body><article><p>${"Текст главы. ".repeat(60)}</p>
            <div class="adv-banner"><img src="https://x.example/promo/banner.png" alt="реклама"/></div>
            <img src="https://ads.example/banner300.png" alt="b"/>
            <img src="https://cdn.example/pic.png" alt="иллюстрация" width="500"/>
            <p>Реклама. ООО «Тест», erid: 1</p></article></body></html>"""
        val page = com.localdoc.ai.parser.WebArticleParser().fromHtml(html, "https://site.example/ch1")
        val images = page.book.blocks.filter { it.isImage }
        assertEquals(listOf("https://cdn.example/pic.png"), images.map { it.image })
        assertTrue(page.book.blocks.none { it.text.contains("erid") })
    }
}
