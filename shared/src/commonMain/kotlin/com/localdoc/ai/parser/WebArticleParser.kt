package com.localdoc.ai.parser

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.localdoc.ai.book.ArticleExtractor
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.DownloadLinkFinder
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.HtmlImageResolver
import com.localdoc.ai.book.ImageCollector
import com.localdoc.ai.book.PageCover
import com.localdoc.ai.book.withImages
import com.localdoc.ai.net.HttpStatusException
import com.localdoc.ai.net.LoginFormDetector
import com.localdoc.ai.net.WebHttp
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.book.WebNavigation
import com.localdoc.ai.book.WebNavigationDetector
import com.localdoc.ai.book.chaptersFromHeadings
import kotlinx.coroutines.Dispatchers
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

/**
 * Загруженная веб-страница в режиме чтения + все её ссылки (для поиска навигации моделью) и
 * ссылки на готовые файлы книги ([downloads]: `<link rel="alternate">`, `<a download>`), если сайт их отдаёт.
 */
class WebPage(val book: Book, val links: List<PageLink>, val downloads: List<PageLink> = emptyList())

/**
 * Страница закрыта логином (HTTP 401/403 или на месте текста форма входа) либо защитой сайта,
 * которую приложение не обходит. [loginUrl] — страница с формой входа, если она найдена.
 */
class LoginRequiredException(
    val pageUrl: String,
    val loginUrl: String?,
    val browserOnly: Boolean = false
) : IllegalStateException(
    if (browserOnly) "Сайт требует проверки в браузере — войдите через встроенный браузер"
    else "Для этой страницы нужен вход на сайт"
) {
    val host: String get() = WebArticleParser.hostOf(pageUrl)
}

/**
 * Веб-страница в режиме чтения (аналог Reader Mode): очистка от рекламы и меню
 * (Readability), разбиение на абзацы и поиск навигации веб-новелл. Страницы грузятся через
 * [WebHttp] — с cookie сохранённого входа.
 */
class WebArticleParser(private val http: () -> WebHttp = { error("HTTP-клиент не настроен") }) {

    suspend fun load(url: String): WebPage = withContext(Dispatchers.Default) {
        val result = http().get(normalizeUrl(url))
        val html = result.text()
        if (LoginFormDetector.isChallengePage(result.status, html)) throw LoginRequiredException(result.url, null, browserOnly = true)
        val doc = Ksoup.parse(html, result.url)
        val form = LoginFormDetector.find(doc, result.url)
        when {
            result.status == 401 || result.status == 403 -> throw LoginRequiredException(result.url, if (form != null) result.url else null)
            !result.isOk -> throw HttpStatusException(result.status, result.url)
            form != null && (doc.body().text().length < LOGIN_WALL_TEXT) -> throw LoginRequiredException(result.url, result.url)
        }
        fromDocument(doc, result.url)
    }

    /** Разбор уже загруженного HTML (тесты, сохранённые страницы). */
    fun fromHtml(html: String, url: String): WebPage = fromDocument(Ksoup.parse(html, url), url)

    /** Оглавление новеллы: список глав со страницы оглавления (все страницы списка, если он разбит на части). */
    suspend fun loadTableOfContents(tocUrl: String): List<PageLink> = withContext(Dispatchers.Default) {
        val all = ArrayList<PageLink>()
        var next: String? = normalizeUrl(tocUrl)
        val seen = HashSet<String>()
        var pages = 0
        while (next != null && seen.add(next) && pages < MAX_TOC_PAGES) {
            pages++
            val result = http().get(next)
            if (!result.isOk) {
                if (pages == 1) throw HttpStatusException(result.status, result.url)
                break
            }
            val doc = Ksoup.parse(result.text(), result.url)
            val chunk = WebNavigationDetector.tableOfContents(doc, result.url)
            val known = all.map { it.url }.toHashSet()
            val fresh = chunk.filter { it.url !in known }
            if (fresh.isEmpty()) break
            all.addAll(fresh)
            // Длинное оглавление разбито на страницы: идём по «Далее», пока приходят новые главы
            next = WebNavigationDetector.detect(doc, result.url).next
        }
        all
    }

    private fun fromDocument(doc: Document, url: String): WebPage {
        // Навигацию и ссылки собираем до очистки: меню и подвалы с «Далее» будут удалены
        val navigation = WebNavigationDetector.detect(doc, url)
        val links = WebNavigationDetector.links(doc, url)
        val downloads = DownloadLinkFinder.declared(doc, url)
        val title = doc.select("h1").firstOrNull()?.text()?.trim().orEmpty().ifBlank { doc.title() }.ifBlank { url }
        val images = ImageCollector()
        val cover = PageCover.find(doc, url, images)
        val blocks = stripPageChrome(ArticleExtractor.extract(doc, HtmlImageResolver(url, images)))
        val book = Book(
            title = title,
            author = hostOf(url),
            format = BookFormat.WEB,
            source = url,
            blocks = blocks,
            chapters = chaptersFromHeadings(blocks, maxLevel = 3),
            web = navigation.takeIf { it != WebNavigation() }
        ).withImages(images, cover)
        return WebPage(book, links, downloads)
    }

    companion object {
        private const val CHROME_EDGE_BLOCKS = 6
        private val CHROME_LABELS = setOf(
            "bookmark", "bookmarks", "add to bookmarks", "chapters list", "chapter list", "table of contents", "contents",
            "next chapter", "previous chapter", "prev chapter", "next", "prev", "previous",
            "закладка", "в закладки", "добавить в закладки", "список глав", "оглавление", "содержание",
            "следующая глава", "предыдущая глава", "далее", "назад"
        )

        /**
         * Кнопки сайта («Bookmark», «Chapters list», «Next chapter»…) попадают в текст отдельными строками по краям
         * страницы и при постраничном листании занимают целую страницу. Убираются только отдельные строки из
         * [CHROME_LABELS] в первых и последних [CHROME_EDGE_BLOCKS] блоках; текст главы не затрагивается.
         */
        internal fun stripPageChrome(blocks: List<Block>): List<Block> {
            val kept = blocks.filterIndexed { index, block ->
                val atEdge = index < CHROME_EDGE_BLOCKS || index >= blocks.size - CHROME_EDGE_BLOCKS
                val label = block.text.trim().trim('«', '»', '[', ']', '|', '•', '·', '→', '←').trim().lowercase()
                !(atEdge && block.type != BlockType.IMAGE && label in CHROME_LABELS)
            }
            return kept.ifEmpty { blocks }
        }

        private const val LOGIN_WALL_TEXT = 1500
        private const val MAX_TOC_PAGES = 60

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
