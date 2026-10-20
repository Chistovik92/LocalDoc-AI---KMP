package com.localdoc.ai.parser

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.network.parseGetRequest
import com.fleeksoft.ksoup.nodes.Document
import com.localdoc.ai.book.ArticleExtractor
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.HtmlImageResolver
import com.localdoc.ai.book.ImageCollector
import com.localdoc.ai.book.PageCover
import com.localdoc.ai.book.withImages
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.book.WebNavigation
import com.localdoc.ai.book.WebNavigationDetector
import com.localdoc.ai.book.chaptersFromHeadings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Результат разбора документа (вход для SmartPipeline). */
data class ParsedDoc(
    val title: String,
    val content: String,
    val source: String = "",
    val pageCount: Int = 1
) {
    val approxTokens: Int get() = content.length / 3 // грубая оценка для русского текста
}

/** Загруженная веб-страница в режиме чтения + все её ссылки (для поиска навигации моделью). */
class WebPage(val book: Book, val links: List<PageLink>)

/**
 * Веб-страница в режиме чтения (аналог Reader Mode): очистка от рекламы и меню
 * (Readability), разбиение на абзацы и поиск навигации веб-новелл.
 */
class WebArticleParser {

    suspend fun load(url: String): WebPage = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(url)
        val doc: Document = Ksoup.parseGetRequest(url = normalizedUrl)
        fromDocument(doc, normalizedUrl)
    }

    /** Разбор уже загруженного HTML (тесты, сохранённые страницы). */
    fun fromHtml(html: String, url: String): WebPage = fromDocument(Ksoup.parse(html, url), url)

    /** Оглавление новеллы: список глав со страницы оглавления. */
    suspend fun loadTableOfContents(tocUrl: String): List<PageLink> = withContext(Dispatchers.IO) {
        val doc = Ksoup.parseGetRequest(url = normalizeUrl(tocUrl))
        WebNavigationDetector.tableOfContents(doc, tocUrl)
    }

    private fun fromDocument(doc: Document, url: String): WebPage {
        // Навигацию и ссылки собираем до очистки: меню и подвалы с «Далее» будут удалены
        val navigation = WebNavigationDetector.detect(doc, url)
        val links = WebNavigationDetector.links(doc, url)
        val title = doc.select("h1").firstOrNull()?.text()?.trim().orEmpty().ifBlank { doc.title() }.ifBlank { url }
        val images = ImageCollector()
        val cover = PageCover.find(doc, url, images)
        val blocks = ArticleExtractor.extract(doc, HtmlImageResolver(url, images))
        val book = Book(
            title = title,
            author = hostOf(url),
            format = BookFormat.WEB,
            source = url,
            blocks = blocks,
            chapters = chaptersFromHeadings(blocks, maxLevel = 3),
            web = navigation.takeIf { it != WebNavigation() }
        ).withImages(images, cover)
        return WebPage(book, links)
    }

    companion object {
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                "://" in trimmed -> throw IllegalArgumentException("Поддерживаются только ссылки http(s)")
                else -> "https://$trimmed"
            }
        }

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")
    }
}
