package com.localdoc.ai.book

import com.fleeksoft.ksoup.nodes.Element

/** Готовый файл книги, который сайт отдаёт целиком. [format] null — тип выяснится по содержимому после загрузки. */
data class DownloadCandidate(val url: String, val format: BookFormat?, val label: String)

/**
 * Поиск ссылок «скачать книгу целиком» на странице описания, оглавления или главы: прямые ссылки на
 * файлы (.epub, .fb2, .fb2.zip, .docx, .rtf, .txt, .pdf…), кнопки «Скачать» со ссылкой на выгрузку и
 * объявленные в разметке альтернативы (`link rel="alternate"`, `a download`). Предпочтение — формату,
 * который лучше сохраняет структуру: EPUB, FB2, FB2.ZIP, DOCX, ODT, RTF, TXT, PDF.
 */
object DownloadLinkFinder {

    private val EXTENSIONS = mapOf(
        "epub" to BookFormat.EPUB, "fb2" to BookFormat.FB2, "fbz" to BookFormat.FB2, "docx" to BookFormat.DOCX,
        "odt" to BookFormat.ODT, "rtf" to BookFormat.RTF, "txt" to BookFormat.TEXT, "pdf" to BookFormat.PDF,
        "doc" to BookFormat.DOC
    )
    private val PREFERENCE = listOf(
        BookFormat.EPUB, BookFormat.FB2, BookFormat.DOCX, BookFormat.ODT, BookFormat.RTF, BookFormat.DOC, BookFormat.TEXT, BookFormat.PDF
    )
    private val ACTION_TEXT = Regex("скачать|скачай|download|загрузить|выгрузить|сохранить как|export", RegexOption.IGNORE_CASE)
    private val ACTION_URL = Regex("/(download|export|get|dl|file)s?(/|\\?|$|\\.)|[?&](download|export|format)=", RegexOption.IGNORE_CASE)
    private val NOT_A_BOOK = Regex(
        "приложен|android|ios|apk|play\\.google|apps\\.apple|itunes|telegram|t\\.me/|vk\\.com|donat|донат|patreon|boosty|читалк|reader app|mobile app",
        RegexOption.IGNORE_CASE
    )
    private val MIME_FORMATS = mapOf(
        "application/epub+zip" to BookFormat.EPUB, "application/x-fictionbook+xml" to BookFormat.FB2,
        "application/x-fictionbook" to BookFormat.FB2, "application/rtf" to BookFormat.RTF, "text/rtf" to BookFormat.RTF,
        "application/pdf" to BookFormat.PDF, "text/plain" to BookFormat.TEXT,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to BookFormat.DOCX,
        "application/vnd.oasis.opendocument.text" to BookFormat.ODT
    )

    /** Альтернативы, объявленные самой страницей: надёжнее любых эвристик по тексту ссылок. */
    fun declared(doc: Element, pageUrl: String): List<PageLink> {
        val result = ArrayList<PageLink>()
        for (link in doc.select("link[rel][href]").filter { l -> l.attr("rel").lowercase().let { "alternate" in it || "acquisition" in it } }) {
            val type = link.attr("type").lowercase().substringBefore(';')
            val url = WebNavigationDetector.resolveUrl(pageUrl, link.attr("href")) ?: continue
            if (type in MIME_FORMATS || link.attr("rel").contains("acquisition", ignoreCase = true)) {
                result.add(PageLink(link.attr("title").ifBlank { type }, url))
            }
        }
        for (a in doc.select("a[download][href]")) {
            val url = WebNavigationDetector.resolveUrl(pageUrl, a.attr("href")) ?: continue
            result.add(PageLink(HtmlBlockBuilder.normalizeSpaces(a.text()).ifBlank { a.attr("download") }, url))
        }
        return result
    }

    /** Подходящие ссылки, лучшие первыми; каждый адрес один раз. */
    fun find(links: List<PageLink>): List<DownloadCandidate> {
        val candidates = ArrayList<DownloadCandidate>()
        for (link in links.distinctBy { it.url }) {
            val label = link.text.take(80)
            if (NOT_A_BOOK.containsMatchIn(label + " " + link.url)) continue
            val format = formatOf(link)
            when {
                format != null -> candidates.add(DownloadCandidate(link.url, format, label))
                ACTION_TEXT.containsMatchIn(link.text) && ACTION_URL.containsMatchIn(link.url) ->
                    candidates.add(DownloadCandidate(link.url, null, label))
            }
        }
        return candidates.sortedBy { c -> c.format?.let { PREFERENCE.indexOf(it).takeIf { i -> i >= 0 } } ?: PREFERENCE.size }
    }

    private fun formatOf(link: PageLink): BookFormat? {
        val path = link.url.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".fb2.zip")) return BookFormat.FB2
        EXTENSIONS[path.substringAfterLast('.', "")]?.let { return it }
        // Выгрузка без расширения в адресе: формат назван в тексте ссылки или параметре (?format=epub)
        val hint = (link.text + " " + link.url.substringAfter('?', "")).lowercase()
        return when {
            ACTION_TEXT.containsMatchIn(link.text) || ACTION_URL.containsMatchIn(link.url) -> when {
                "epub" in hint -> BookFormat.EPUB
                "fb2" in hint -> BookFormat.FB2
                "docx" in hint -> BookFormat.DOCX
                "rtf" in hint -> BookFormat.RTF
                else -> null
            }
            else -> null
        }
    }

    /** Формат, объявленный страницей для этой ссылки (по MIME-типу в `declared`). */
    fun formatOfMime(mime: String): BookFormat? = MIME_FORMATS[mime.lowercase().substringBefore(';').trim()]
}
