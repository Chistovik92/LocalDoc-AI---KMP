package com.localdoc.ai.book

import com.localdoc.ai.parser.WebArticleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebArticleTest {

    private val longParagraph = "Это длинный абзац статьи, в котором много текста, запятых, смысла и подробностей, чтобы его выбрал алгоритм чтения."

    @Test
    fun `ad block in the middle of an article does not cut the text`() {
        val html = """
            <html><head><title>Статья</title></head><body>
              <header><nav><a href="/">Главная</a></nav></header>
              <article>
                <h1>Заголовок статьи</h1>
                <p>Первый абзац. $longParagraph</p>
                <div class="ad-banner"><p>Купите наш товар со скидкой!</p></div>
                <div id="yandex_rtb_R-A-123"><p>Рекламное объявление</p></div>
                <p>Реклама. ООО «Ромашка», ИНН 7700000000, erid: 2SDnjeXYZ</p>
                <p>Второй абзац после рекламы. $longParagraph</p>
                <p>Третий абзац. $longParagraph</p>
              </article>
              <aside class="sidebar"><p>Популярное</p></aside>
              <footer>Подвал</footer>
            </body></html>
        """.trimIndent()
        val page = WebArticleParser().fromHtml(html, "https://example.com/news/1")
        val texts = page.book.blocks.map { it.text }

        assertTrue(texts.any { it.startsWith("Первый абзац") })
        assertTrue(texts.any { it.startsWith("Второй абзац после рекламы") })
        assertTrue(texts.any { it.startsWith("Третий абзац") })
        assertFalse(texts.any { "Купите" in it || "Рекламное" in it || "erid" in it || "Популярное" in it || "Подвал" in it })
    }

    @Test
    fun `article split into sections keeps intro and all sections without wiki service links`() {
        val html = """
            <html><body><div class="mw-parser-output">
              <section><table class="infobox"><tr><td>Жанр</td><td>роман</td></tr></table>
                <p>Вступление статьи. $longParagraph<sup class="reference">[1]</sup></p></section>
              <section><h2>Сюжет<span class="mw-editsection">[править | править код]</span></h2>
                <p>Сюжет первый. $longParagraph</p><p>Сюжет второй. $longParagraph</p><p>Сюжет третий. $longParagraph</p></section>
              <section><h2>История</h2><p>История создания. $longParagraph</p></section>
            </div></body></html>
        """.trimIndent()
        val texts = WebArticleParser().fromHtml(html, "https://ru.wikipedia.org/wiki/X").book.blocks.map { it.text }
        assertTrue(texts.first().startsWith("Вступление статьи"), "вступление не должно теряться: $texts")
        assertTrue(texts.any { it.startsWith("История создания") })
        assertFalse(texts.any { "править" in it || "[1]" in it || "Жанр" in it })
    }

    @Test
    fun `next chapter, previous and table of contents links are detected`() {
        val html = """
            <html><head><title>Глава 5</title><link rel="prev" href="/novel/4"></head><body>
              <article><h1>Глава 5</h1><p>$longParagraph</p><p>$longParagraph</p></article>
              <div class="nav"><a href="/novel/4">« Назад</a> <a href="/novel">Оглавление</a> <a href="/novel/6">Следующая глава</a></div>
            </body></html>
        """.trimIndent()
        val nav = WebArticleParser().fromHtml(html, "https://ranobe.example/novel/5").book.web
        assertEquals("https://ranobe.example/novel/6", nav?.next)
        assertEquals("https://ranobe.example/novel/4", nav?.prev)
        assertEquals("https://ranobe.example/novel", nav?.toc)
    }

    @Test
    fun `page without navigation has no web metadata`() {
        val html = "<html><body><article><p>$longParagraph</p></article></body></html>"
        assertNull(WebArticleParser().fromHtml(html, "https://example.com/a").book.web)
    }

    @Test
    fun `table of contents is the largest group of chapter links`() {
        val chapters = (1..8).joinToString("") { "<li><a href=\"/novel/$it\">Глава $it</a></li>" }
        val html = "<html><body><ul class=\"menu\"><li><a href=\"/\">Главная</a></li><li><a href=\"/top\">Топ</a></li></ul><ol>$chapters</ol></body></html>"
        val doc = com.fleeksoft.ksoup.Ksoup.parse(html, "https://ranobe.example/novel")
        val toc = WebNavigationDetector.tableOfContents(doc, "https://ranobe.example/novel")
        assertEquals(8, toc.size)
        assertEquals("https://ranobe.example/novel/1", toc.first().url)
    }

    @Test
    fun `relative urls are resolved`() {
        assertEquals("https://a.ru/b/c", WebNavigationDetector.resolveUrl("https://a.ru/b/x.html", "c"))
        assertEquals("https://a.ru/c", WebNavigationDetector.resolveUrl("https://a.ru/b/x.html", "../c"))
        assertEquals("https://a.ru/z?p=2", WebNavigationDetector.resolveUrl("https://a.ru/b/x", "/z?p=2"))
        assertEquals("https://cdn.ru/x", WebNavigationDetector.resolveUrl("https://a.ru/", "//cdn.ru/x"))
        assertNull(WebNavigationDetector.resolveUrl("https://a.ru/", "#top"))
    }
}
