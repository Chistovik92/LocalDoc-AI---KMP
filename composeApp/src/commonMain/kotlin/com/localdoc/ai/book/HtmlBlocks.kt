package com.localdoc.ai.book

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Преобразование HTML/XHTML в блоки читалки. Инлайн-текст копится в буфер и
 * сбрасывается абзацем при встрече блочного элемента — это устойчиво к «грязной»
 * вёрстке, где текст лежит прямо в div вперемешку с тегами.
 */
internal class HtmlBlockBuilder {

    private val blocks = ArrayList<Block>()
    private val inline = StringBuilder()

    fun build(root: Element): List<Block> {
        walk(root, listDepth = 0)
        flush()
        return blocks.filterNot { AdFilter.isAdText(it.text) }
    }

    private fun walk(element: Element, listDepth: Int) {
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> inline.append(node.text())
                is Element -> handleElement(node, listDepth)
                else -> Unit
            }
        }
    }

    private fun handleElement(el: Element, listDepth: Int) {
        val tag = el.normalName()
        when {
            tag in SKIP_TAGS -> Unit
            tag == "br" -> inline.append('\n')
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> emit(BlockType.HEADING, el.text(), tag[1] - '0')
            tag == "p" || tag == "dd" || tag == "dt" || tag == "figcaption" -> {
                flush()
                walk(el, listDepth)
                flush()
            }
            tag == "li" -> {
                flush()
                // Вложенные списки обрабатываем отдельно, чтобы не склеивать их с пунктом
                val nested = el.children().filter { it.normalName() == "ul" || it.normalName() == "ol" }
                nested.forEach { it.remove() }
                emit(BlockType.LIST_ITEM, el.text(), listDepth)
                nested.forEach { walk(it, listDepth + 1) }
            }
            tag == "ul" || tag == "ol" -> {
                flush()
                walk(el, listDepth + 1)
                flush()
            }
            tag == "blockquote" -> emit(BlockType.QUOTE, el.text())
            tag == "pre" -> emit(BlockType.CODE, el.wholeTextSafe())
            tag == "tr" -> emit(BlockType.PARAGRAPH, el.children().joinToString(" · ") { it.text() })
            tag in BLOCK_CONTAINERS -> {
                flush()
                walk(el, listDepth)
                flush()
            }
            else -> walk(el, listDepth) // инлайн: span, a, b, i, em, strong, sup…
        }
    }

    private fun emit(type: BlockType, text: String, level: Int = 0) {
        flush()
        val clean = if (type == BlockType.CODE) text.trimEnd() else normalizeSpaces(text)
        if (clean.isNotBlank()) blocks.add(Block(type, clean, level))
    }

    private fun flush() {
        if (inline.isEmpty()) return
        val text = inline.toString()
        inline.clear()
        // <br><br> внутри одного контейнера — это разрыв абзаца
        text.split(Regex("\n\\s*\n")).forEach { part ->
            val clean = normalizeSpaces(part)
            if (clean.isNotBlank()) blocks.add(Block(BlockType.PARAGRAPH, clean))
        }
    }

    private fun Element.wholeTextSafe(): String =
        childNodes().joinToString("") { if (it is TextNode) it.text() else (it as? Element)?.text().orEmpty() }

    companion object {
        private val SKIP_TAGS = setOf(
            "script", "style", "noscript", "iframe", "svg", "canvas", "template", "button",
            "input", "select", "textarea", "form", "nav", "head", "img", "video", "audio", "object", "embed"
        )
        private val BLOCK_CONTAINERS = setOf(
            "div", "section", "article", "main", "body", "header", "footer", "aside", "figure",
            "table", "tbody", "thead", "tfoot", "dl", "center", "hr", "html"
        )

        fun normalizeSpaces(s: String): String =
            s.replace(' ', ' ').replace(Regex("[ \\t\\r\\n]+"), " ").trim()
    }
}

/** Распознавание рекламных и служебных элементов страницы. */
object AdFilter {

    // Явная реклама: такие узлы удаляются всегда
    private val AD_TOKEN = Regex(
        "(^|[\\s_\\-])(ad|ads|adv|advert\\w*|advertising|banner\\w*|sponsor\\w*|promo\\w*|reklama|" +
            "adfox|adsense|adsbygoogle|adriver|yandex[_\\-]?rtb|rtb|yandex[_\\-]?direct|direct[_\\-]?adv\\w*|" +
            "taboola|outbrain|smi2|mgid|teaser\\w*|commercial|partner[_\\-]?block|native[_\\-]?ad\\w*|dfp|gpt[_\\-]?ad)" +
            "([\\s_\\-]|\\d|$)",
        RegexOption.IGNORE_CASE
    )

    // Служебные блоки: удаляются, если не похожи на основной контент
    private val JUNK_TOKEN = Regex(
        "comment|share|social|subscribe|newsletter|related|recommend|popular|sidebar|widget|menu|" +
            "breadcrumb|cookie|popup|modal|footer|header|navbar|tags|author[_\\-]?bio|read[_\\-]?more|also",
        RegexOption.IGNORE_CASE
    )
    private val CONTENT_TOKEN = Regex("article|content|entry|post|story|text|body|main|материал", RegexOption.IGNORE_CASE)

    // Маркировка рекламы в рунете («Реклама. ООО … erid: …») и типовые вставки
    private val AD_TEXT = Regex(
        "^(реклама|advertisement|advertising|sponsored|спонсорский материал|партн[её]рский материал|" +
            "на правах рекламы|промо)\\b.{0,200}$|\\berid\\s*[:：]|^(читайте также|смотрите также|подписывайтесь|" +
            "реклама\\s*\\d+\\+)\\b.{0,160}$",
        RegexOption.IGNORE_CASE
    )

    fun isAdText(text: String): Boolean {
        val t = text.trim()
        return t.length <= 400 && AD_TEXT.containsMatchIn(t)
    }

    fun isAdElement(el: Element): Boolean {
        val signature = el.className() + " " + el.id()
        if (AD_TOKEN.containsMatchIn(signature)) return true
        if (el.hasAttributeLike("data-ad") || el.normalName() == "ins") return true
        return false
    }

    fun isJunkElement(el: Element): Boolean {
        val signature = el.className() + " " + el.id() + " " + el.attr("role")
        if (el.attr("aria-hidden") == "true") return true
        return JUNK_TOKEN.containsMatchIn(signature) && !CONTENT_TOKEN.containsMatchIn(signature)
    }

    private fun Element.hasAttributeLike(prefix: String): Boolean =
        attributes().any { it.key.startsWith(prefix) }
}

/**
 * Извлечение основного текста статьи (по мотивам Mozilla Readability):
 * удаление рекламы и служебных блоков, выбор контейнера с наибольшей
 * «плотностью» абзацев и малой долей ссылок.
 */
object ArticleExtractor {

    fun extract(doc: Element): List<Block> {
        doc.select("script, style, noscript, iframe, svg, form, nav, button, template, link, meta").remove()
        // Служебные элементы вики-движков: «[править]», сноски [1], инфобоксы, навигационные шаблоны
        doc.select(
            ".mw-editsection, sup.reference, .reference, .noprint, .navbox, .hatnote, .infobox, .metadata, " +
                ".mw-jump-link, .mw-references-wrap, .reflist, .toc, #toc, .thumb, figure"
        ).remove()
        removeNoise(doc)
        val root = pickContentRoot(doc)
        return HtmlBlockBuilder().build(root)
    }

    private fun removeNoise(doc: Element) {
        val all = doc.select("*").toList()
        val bodyTextLength = doc.text().length.coerceAtLeast(1)
        for (el in all) {
            if (el.parent() == null) continue // уже удалён вместе с предком
            val tag = el.normalName()
            if (tag == "html" || tag == "body" || tag == "article" || tag == "main") continue
            val textShare = el.text().length.toFloat() / bodyTextLength
            when {
                // Рекламный блок не может содержать бо́льшую часть страницы — это обёртка контента
                AdFilter.isAdElement(el) && textShare < 0.5f -> el.remove()
                AdFilter.isJunkElement(el) && textShare < 0.3f -> el.remove()
                tag == "aside" || tag == "footer" -> el.remove()
                tag == "header" && el.select("h1").isEmpty() -> el.remove()
            }
        }
    }

    private fun pickContentRoot(doc: Element): Element {
        doc.select("[itemprop=articleBody], article").maxByOrNull { it.text().length }
            ?.takeIf { it.text().length > 500 }
            ?.let { return it }

        val scores = HashMap<Element, Double>()
        for (p in doc.select("p, pre, td, blockquote")) {
            val text = p.text()
            if (text.length < 25) continue
            val score = 1.0 + text.count { it == ',' } + minOf(text.length / 100, 3)
            p.parent()?.let { parent ->
                scores[parent] = (scores[parent] ?: 0.0) + score
                parent.parent()?.let { grand -> scores[grand] = (scores[grand] ?: 0.0) + score / 2 }
            }
        }
        val best = scores.entries
            .map { (el, score) -> el to score * (1 - linkDensity(el)) }
            .maxByOrNull { it.second }
            ?.first
            ?.let(::promoteToSharedParent)
        return best ?: doc.select("main, [role=main]").firstOrNull() ?: doc.select("body").firstOrNull() ?: doc
    }

    /**
     * Статья часто разбита на секции (Википедия: вступление и каждый раздел — отдельный section).
     * Если у родителя заметно больше абзацного текста, чем у лучшей секции, берём родителя,
     * иначе вступление и соседние разделы потеряются.
     */
    private fun promoteToSharedParent(start: Element): Element {
        var current = start
        repeat(3) {
            val parent = current.parent() ?: return current
            if (parent.normalName() == "body" || parent.normalName() == "html") return current
            if (paragraphText(parent) >= paragraphText(current) * 1.25 && linkDensity(parent) < 0.35) current = parent else return current
        }
        return current
    }

    private fun paragraphText(el: Element): Int =
        el.select("p, li, blockquote").sumOf { p -> p.text().length.takeIf { it >= 25 } ?: 0 }

    private fun linkDensity(el: Element): Double {
        val total = el.text().length
        if (total == 0) return 1.0
        val links = el.select("a").sumOf { it.text().length }
        return links.toDouble() / total
    }
}
