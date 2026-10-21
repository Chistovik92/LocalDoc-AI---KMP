package com.localdoc.ai.book

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Преобразование HTML/XHTML в блоки читалки. Инлайн-текст копится в буфер и
 * сбрасывается абзацем при встрече блочного элемента — это устойчиво к «грязной»
 * вёрстке, где текст лежит прямо в div вперемешку с тегами.
 */
internal class HtmlBlockBuilder(private val images: ImageResolver? = null) {

    private val blocks = ArrayList<Block>()
    private var inline = DocxParser.RunTextBuilder()
    private var inlineEmpty = true
    private var pendingBr = false
    private var bold = 0
    private var italic = 0
    private var code = 0

    fun build(root: Element): List<Block> {
        walk(root, listDepth = 0)
        flush()
        return blocks.filterNot { AdFilter.isAdText(it.text) }
    }

    private fun walk(element: Element, listDepth: Int) {
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> appendInline(node.text())
                is Element -> handleElement(node, listDepth)
                else -> Unit
            }
        }
    }

    private fun appendInline(text: String) {
        if (text.isEmpty()) return
        inline.append(text, bold > 0, italic > 0, code > 0)
        inlineEmpty = false
        pendingBr = false
    }

    private fun handleElement(el: Element, listDepth: Int) {
        val tag = el.normalName()
        when {
            tag == "img" -> imageBlock(el)
            tag in SKIP_TAGS -> Unit
            tag == "br" -> {
                // Два <br> подряд — разрыв абзаца, одиночный — просто пробел
                if (pendingBr) { flush(); pendingBr = false } else { appendInline(" "); pendingBr = true }
            }
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> emit(BlockType.HEADING, el, tag[1] - '0')
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
                emit(BlockType.LIST_ITEM, el, (listDepth - 1).coerceAtLeast(0))
                nested.forEach { walk(it, listDepth + 1) }
            }
            tag == "ul" || tag == "ol" -> {
                flush()
                walk(el, listDepth + 1)
                flush()
            }
            tag == "blockquote" -> emit(BlockType.QUOTE, el)
            tag == "pre" -> emitCode(el)
            tag == "tr" -> {
                val cells = el.children().joinToString(" · ") { it.text() }
                flush()
                val clean = normalizeSpaces(cells)
                if (clean.isNotBlank()) blocks.add(Block(BlockType.PARAGRAPH, clean))
            }
            tag in BLOCK_CONTAINERS -> {
                flush()
                walk(el, listDepth)
                flush()
            }
            else -> {
                // инлайн: span, a, b, i, em, strong, code, sup…
                val b = tag == "b" || tag == "strong"
                val i = tag == "i" || tag == "em" || tag == "cite" || tag == "dfn" || tag == "var"
                val c = tag == "code" || tag == "tt" || tag == "kbd" || tag == "samp"
                if (b) bold++
                if (i) italic++
                if (c) code++
                walk(el, listDepth)
                if (b) bold--
                if (i) italic--
                if (c) code--
            }
        }
    }

    /** Картинка в тексте: ленивые атрибуты (`data-src`), без иконок, логотипов и «пикселей». */
    private fun imageBlock(el: Element) {
        val resolver = images ?: return
        val src = IMAGE_SOURCE_ATTRS.map { el.attr(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return
        val width = el.attr("width").trim().removeSuffix("px").toIntOrNull()
        val height = el.attr("height").trim().removeSuffix("px").toIntOrNull()
        if ((width != null && width < MIN_IMAGE_SIDE) || (height != null && height < MIN_IMAGE_SIDE)) return
        if (JUNK_IMAGE.containsMatchIn(el.className() + " " + el.id() + " " + src.take(300))) return
        val ref = resolver.resolve(src) ?: return
        flush()
        blocks.add(Block(BlockType.IMAGE, normalizeSpaces(el.attr("alt")).take(300), image = ref))
    }

    /** Блок целиком из содержимого элемента, с сохранением жирного / курсива / кода. */
    private fun emit(type: BlockType, el: Element, level: Int = 0) {
        flush()
        val saved = Triple(bold, italic, code)
        val outer = inline
        inline = DocxParser.RunTextBuilder()
        bold = 0; italic = 0; code = 0
        walkInlineOnly(el)
        val (text, spans) = inline.build()
        inline = outer
        bold = saved.first; italic = saved.second; code = saved.third
        if (text.isNotBlank()) blocks.add(Block(type, text, level, spans))
    }

    /** Обход содержимого блока без создания вложенных блоков (внутри заголовка, пункта, цитаты). */
    private fun walkInlineOnly(el: Element) {
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> inline.append(node.text(), bold > 0, italic > 0, code > 0)
                is Element -> {
                    val tag = node.normalName()
                    if (tag in SKIP_TAGS) continue
                    if (tag == "br") { inline.append(" ", false, false, false); continue }
                    val b = tag == "b" || tag == "strong"
                    val i = tag == "i" || tag == "em" || tag == "cite" || tag == "dfn" || tag == "var"
                    val c = tag == "code" || tag == "tt" || tag == "kbd" || tag == "samp"
                    if (b) bold++
                    if (i) italic++
                    if (c) code++
                    walkInlineOnly(node)
                    if (b) bold--
                    if (i) italic--
                    if (c) code--
                }
                else -> Unit
            }
        }
    }

    private fun emitCode(el: Element) {
        flush()
        val text = el.wholeTextSafe().trimEnd()
        if (text.isNotBlank()) blocks.add(Block(BlockType.CODE, text))
    }

    private fun flush() {
        if (inlineEmpty) return
        val (text, spans) = inline.build()
        inline = DocxParser.RunTextBuilder()
        inlineEmpty = true
        pendingBr = false
        if (text.isNotBlank()) blocks.add(Block(BlockType.PARAGRAPH, text, 0, spans))
    }

    private fun Element.wholeTextSafe(): String =
        childNodes().joinToString("") { if (it is TextNode) it.text() else (it as? Element)?.text().orEmpty() }

    companion object {
        private val IMAGE_SOURCE_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "src")
        private const val MIN_IMAGE_SIDE = 48
        private val JUNK_IMAGE = Regex("(^|[\\s_\\-/.])(icon|logo|avatar|emoji|smiley|sprite|pixel|spacer|badge|tracking|blank|loader|spinner|banner|ads?|adv|advert|promo|sponsor|doubleclick|googlesyndication|adfox|adriver|teaser|counter)([\\s_\\-/.\\d]|$)", RegexOption.IGNORE_CASE)
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

    // Водяные знаки и призывы сайтов с книгами, фанфиками и веб-новеллами: короткие строки-вставки
    private val PROMO_TEXT = Regex(
        "^(скачано (с|на)|скачать (книгу|полную)|читайте (нас|продолжение|полную версию|книгу целиком|на нашем)|" +
            "книга (взята|скачана|размещена) (с|на)|источник:|оригинал( статьи)?:|купить (книгу|полную версию)|" +
            "продолжение (читайте |следует на )?на |все права защищены|копирование (запрещено|разрешено только)|" +
            "поддержите (автора|проект|нас)|поддержать (автора|проект)|sponsored by|read (more|the full|the rest) |" +
            "follow us|visit (our|my) (site|website)|buy me a coffee|support the author|" +
            "перевод( выполнен)?:?\\s*(команд|групп|студи)[а-яё]*)\\b.{0,160}$|" +
            "^(присоединяйтесь|вступайте|подписывайтесь|подписаться|join|subscribe)\\b.{0,120}" +
            "(групп|канал|telegram|телеграм|vk\\.com|вконтакте|boosty|patreon|сайт|сообществ|discord|channel|newsletter).{0,120}$|" +
            "^(https?://\\S+|www\\.\\S+|[a-z0-9-]+\\.(ru|com|net|org|ua|by|kz|su|club|site|online))\\s*$|" +
            "(patreon\\.com|boosty\\.to|t\\.me/|vk\\.com/|ko-fi\\.com)\\S*\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun isAdText(text: String): Boolean {
        val t = text.trim()
        return (t.length <= 400 && AD_TEXT.containsMatchIn(t)) || (t.length <= 240 && PROMO_TEXT.containsMatchIn(t))
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

    fun extract(doc: Element, images: ImageResolver? = null): List<Block> {
        doc.select("script, style, noscript, iframe, svg, form, nav, button, template, link, meta").remove()
        // Служебные элементы вики-движков: «[править]», сноски [1], инфобоксы, навигационные шаблоны
        doc.select(
            ".mw-editsection, sup.reference, .reference, .noprint, .navbox, .hatnote, .infobox, .metadata, " +
                ".mw-jump-link, .mw-references-wrap, .reflist, .toc, #toc"
        ).remove()
        removeNoise(doc)
        val root = pickContentRoot(doc)
        return HtmlBlockBuilder(images).build(root)
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

/**
 * Книга без рекламы: убирает блоки-вставки (реклама, водяные знаки сайтов, призывы подписаться) из любого
 * источника — EPUB, FB2, DOCX, TXT, скачанных глав. Заголовки глав не удаляются; индексы глав пересчитываются.
 */
fun Book.withoutAds(): Book {
    val keep = blocks.map { b -> b.type == BlockType.IMAGE || b.type == BlockType.HEADING || !AdFilter.isAdText(b.text) }
    if (keep.all { it }) return this
    val newIndex = IntArray(blocks.size + 1)
    var next = 0
    for (i in blocks.indices) {
        newIndex[i] = next
        if (keep[i]) next++
    }
    newIndex[blocks.size] = next
    return copy(
        blocks = blocks.filterIndexed { i, _ -> keep[i] },
        chapters = chapters.map { it.copy(startBlock = newIndex[it.startBlock.coerceIn(0, blocks.size)]) }
            .filter { it.startBlock < next }.distinctBy { it.startBlock }
    )
}
