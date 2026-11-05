package com.localdoc.ai.book

import com.fleeksoft.ksoup.nodes.Element
import kotlinx.serialization.Serializable

/** Ссылка страницы: видимый текст и абсолютный адрес. */
@Serializable
data class PageLink(val text: String, val url: String)

/** Навигация веб-новеллы: соседние главы и оглавление. */
@Serializable
data class WebNavigation(
    val next: String? = null,
    val prev: String? = null,
    val toc: String? = null
)

/**
 * Поиск маркеров «Следующая глава / Предыдущая / Оглавление» на странице.
 * Работает по rel=next/prev, тексту ссылок и классам; если эвристики молчат,
 * список ссылок отдаётся локальной модели (см. SmartPipeline.pickNavigationLink).
 */
object WebNavigationDetector {

    private val NEXT_TEXT = Regex(
        "^(следующая( глава| страница| часть)?|след\\.?( глава)?|далее|дальше|вперёд|вперед|next( chapter| page)?|" +
            "глава вперёд|читать далее|продолжение|[>»→]+|>>)\\s*[>»→]*$",
        RegexOption.IGNORE_CASE
    )
    private val PREV_TEXT = Regex(
        "^[<«←]*\\s*(предыдущая( глава| страница| часть)?|пред\\.?( глава)?|назад|prev(ious)?( chapter| page)?|" +
            "глава назад|[<«←]+|<<)$",
        RegexOption.IGNORE_CASE
    )
    private val TOC_TEXT = Regex(
        "^(к )?(оглавлени[юе]|содержани[юе]|список глав|все главы|главы|table of contents|contents|chapters|index|" +
            "к списку глав|к книге|на страницу книги)$",
        RegexOption.IGNORE_CASE
    )
    private val NEXT_CLASS = Regex("(^|[\\s_\\-])next([\\s_\\-]|$)", RegexOption.IGNORE_CASE)
    private val PREV_CLASS = Regex("(^|[\\s_\\-])(prev|previous)([\\s_\\-]|$)", RegexOption.IGNORE_CASE)
    private val CHAPTER_HINT = Regex("глава|chapter|том|часть|\\bгл\\.|^\\d+", RegexOption.IGNORE_CASE)

    /** Все ссылки страницы с абсолютными адресами (вызывать до очистки документа). */
    fun links(doc: Element, pageUrl: String): List<PageLink> =
        doc.select("a[href]").mapNotNull { a ->
            val url = resolveUrl(pageUrl, a.attr("href")) ?: return@mapNotNull null
            val text = HtmlBlockBuilder.normalizeSpaces(a.text().ifBlank { a.attr("title").ifBlank { a.attr("aria-label") } })
            PageLink(text, url)
        }

    fun detect(doc: Element, pageUrl: String): WebNavigation {
        val relNext = doc.select("link[rel=next], a[rel=next]").firstOrNull()?.attr("href")
        val relPrev = doc.select("link[rel=prev], a[rel=prev], link[rel=previous]").firstOrNull()?.attr("href")
        val anchors = doc.select("a[href]")

        fun byText(regex: Regex) = anchors.firstOrNull { regex.matches(HtmlBlockBuilder.normalizeSpaces(it.text())) }
        fun byClass(regex: Regex) = anchors.firstOrNull { regex.containsMatchIn(it.className() + " " + it.id()) }

        val next = relNext ?: (byText(NEXT_TEXT) ?: byClass(NEXT_CLASS))?.attr("href")
        val prev = relPrev ?: (byText(PREV_TEXT) ?: byClass(PREV_CLASS))?.attr("href")
        val toc = byText(TOC_TEXT)?.attr("href")

        return WebNavigation(
            next = next?.let { resolveUrl(pageUrl, it) }?.takeIf { it != stripFragment(pageUrl) },
            prev = prev?.let { resolveUrl(pageUrl, it) }?.takeIf { it != stripFragment(pageUrl) },
            toc = toc?.let { resolveUrl(pageUrl, it) }
        )
    }

    /**
     * Оглавление: самая большая группа однотипных ссылок (общий родитель, тот же сайт),
     * похожих на главы. Порядок сохраняется, дубликаты убираются.
     */
    fun tableOfContents(doc: Element, pageUrl: String): List<PageLink> {
        val host = hostOf(pageUrl)
        val groups = LinkedHashMap<Element, MutableList<PageLink>>()
        for (a in doc.select("a[href]")) {
            val url = resolveUrl(pageUrl, a.attr("href")) ?: continue
            if (hostOf(url) != host || stripFragment(url) == stripFragment(pageUrl)) continue
            val text = HtmlBlockBuilder.normalizeSpaces(a.text())
            if (text.isBlank()) continue
            // Группируем по контейнеру списка: li/tr поднимаемся ещё на уровень
            val holder = a.parent()?.let { p -> if (p.normalName() in setOf("li", "td", "tr", "span", "div", "p")) p.parent() ?: p else p }
                ?: continue
            groups.getOrPut(holder) { ArrayList() }.add(PageLink(text, url))
        }
        val best = groups.values
            .filter { it.size >= 3 }
            .maxByOrNull { group -> group.size + group.count { CHAPTER_HINT.containsMatchIn(it.text) } * 2 }
            ?: return emptyList()
        return best.distinctBy { it.url }
    }

    fun resolveUrl(base: String, href: String): String? {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("#") || h.startsWith("javascript:", true) || h.startsWith("mailto:", true)) return null
        if (h.startsWith("http://", true) || h.startsWith("https://", true)) return stripFragment(h)
        val scheme = base.substringBefore("://", "https")
        if (h.startsWith("//")) return stripFragment("$scheme:$h")
        val origin = scheme + "://" + base.substringAfter("://").substringBefore('/').substringBefore('?')
        if (h.startsWith("/")) return stripFragment(origin + h)
        val basePath = base.substringAfter("://").substringBefore('?').substringBefore('#')
        val dir = if ('/' in basePath) basePath.substringAfter('/', "").substringBeforeLast('/', "") else ""
        val resolved = normalizePath((if (dir.isEmpty()) "" else "$dir/") + h.substringBefore('#').substringBefore('?'))
        val query = h.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        return stripFragment("$origin/$resolved$query")
    }

    private fun stripFragment(url: String) = url.substringBefore('#')

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()
}
