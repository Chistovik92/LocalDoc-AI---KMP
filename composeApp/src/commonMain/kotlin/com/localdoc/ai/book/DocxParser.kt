package com.localdoc.ai.book

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

/**
 * Word `.docx` (Office Open XML): ZIP → `word/document.xml`. Сохраняем структуру, нужную читалке:
 * абзацы, заголовки (по стилю или уровню структуры), элементы списков, цитаты, код, таблицы (строка → абзац),
 * жирный/курсив/моноширинный текст. Картинки, сноски и колонтитулы пропускаются.
 */
object DocxParser {

    private const val DOCUMENT = "word/document.xml"
    private val HEADING_STYLE = Regex("^(?:heading|заголовок)\\s*(\\d)$", RegexOption.IGNORE_CASE)
    private val MONO_FONTS = listOf("courier", "consolas", "monospace", "menlo", "mono")

    fun parse(zip: ZipArchive, fallbackTitle: String): Book {
        val xml = zip.readText(DOCUMENT) ?: throw IllegalArgumentException("Это не документ Word (.docx): нет word/document.xml")
        val ctx = DocxContext(readStyles(zip), readRelationships(zip), zip, ImageCollector())
        val body = Ksoup.parse(xml, Parser.xmlParser(), "").getElementsByTag("w:body").firstOrNull()
            ?: throw IllegalArgumentException("Повреждённый .docx: в документе нет тела")

        val blocks = ArrayList<Block>()
        walkBody(body, ctx, blocks)

        val core = zip.readText("docProps/core.xml")?.let { Ksoup.parse(it, Parser.xmlParser(), "") }
        val title = core?.getElementsByTag("dc:title")?.firstOrNull()?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = core?.getElementsByTag("dc:creator")?.firstOrNull()?.text()?.trim().orEmpty()
        val thumbnail = listOf("docProps/thumbnail.jpeg", "docProps/thumbnail.png", "docProps/thumbnail.jpg")
            .firstNotNullOfOrNull { name -> runCatching { zip.read(name) }.getOrNull()?.let(ctx.images::add) }
        return Book(title = title, author = author, format = BookFormat.DOCX, blocks = blocks, chapters = chaptersFromHeadings(blocks))
            .withImages(ctx.images, thumbnail)
    }

    /** Состояние разбора документа: стили, связи (rId → путь в архиве) и собранные картинки. */
    private class DocxContext(val styles: Map<String, String>, val rels: Map<String, String>, val zip: ZipArchive, val images: ImageCollector)

    /** `word/_rels/document.xml.rels`: идентификатор связи → путь цели относительно `word/`. */
    private fun readRelationships(zip: ZipArchive): Map<String, String> {
        val xml = zip.readText("word/_rels/document.xml.rels") ?: return emptyMap()
        return Ksoup.parse(xml, Parser.xmlParser(), "").getElementsByTag("Relationship")
            .filter { it.attr("TargetMode") != "External" }
            .associate { it.attr("Id") to it.attr("Target") }
    }

    /** Картинки абзаца (`a:blip r:embed`, `v:imagedata r:id`) в порядке появления. */
    private fun paragraphImages(p: Element, ctx: DocxContext): List<Block> {
        val ids = (p.getElementsByTag("a:blip").map { it.attr("r:embed") } + p.getElementsByTag("v:imagedata").map { it.attr("r:id") })
            .filter { it.isNotEmpty() }
        val alt = p.getElementsByTag("wp:docPr").firstOrNull()?.attr("descr")?.trim().orEmpty()
        return ids.mapNotNull { rid ->
            val target = ctx.rels[rid] ?: return@mapNotNull null
            val ref = zipImage(ctx.zip, "word/", target, ctx.images) ?: return@mapNotNull null
            Block(BlockType.IMAGE, alt, image = ref)
        }
    }

    /** styleId → человеческое имя стиля («heading 1», «List Bullet», «Quote»). */
    private fun readStyles(zip: ZipArchive): Map<String, String> {
        val xml = zip.readText("word/styles.xml") ?: return emptyMap()
        return Ksoup.parse(xml, Parser.xmlParser(), "").getElementsByTag("w:style").associate { style ->
            style.attr("w:styleId") to (style.getElementsByTag("w:name").firstOrNull()?.attr("w:val").orEmpty())
        }
    }

    private fun walkBody(parent: Element, ctx: DocxContext, out: MutableList<Block>) {
        for (child in parent.children()) {
            when (child.normalName()) {
                "w:p" -> {
                    paragraph(child, ctx.styles)?.let(out::add)
                    out.addAll(paragraphImages(child, ctx))
                }
                "w:tbl" -> table(child, ctx, out)
                "w:sdt", "w:sdtcontent", "w:customxml", "w:smarttag" -> walkBody(child, ctx, out)
            }
        }
    }

    private fun table(table: Element, ctx: DocxContext, out: MutableList<Block>) {
        for (row in table.getElementsByTag("w:tr")) {
            val cells = row.children().filter { it.normalName() == "w:tc" }.mapNotNull { cell ->
                val cellBlocks = ArrayList<Block>()
                walkBody(cell, ctx, cellBlocks)
                cellBlocks.filterNot { it.isImage }.joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }
            }
            if (cells.isNotEmpty()) out.add(Block(BlockType.PARAGRAPH, cells.joinToString(" | ")))
        }
    }

    private fun paragraph(p: Element, styles: Map<String, String>): Block? {
        val props = p.children().firstOrNull { it.normalName() == "w:ppr" }
        val styleId = props?.getElementsByTag("w:pstyle")?.firstOrNull()?.attr("w:val").orEmpty()
        val styleName = styles[styleId].orEmpty().ifBlank { styleId }
        val outline = props?.getElementsByTag("w:outlinelvl")?.firstOrNull()?.attr("w:val")?.toIntOrNull()

        val builder = RunTextBuilder()
        collectRuns(p, builder)
        val (text, spans) = builder.build()
        if (text.isBlank()) return null

        val headingLevel = HEADING_STYLE.find(styleName.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?: HEADING_STYLE.find(styleId.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?: outline?.plus(1)?.takeIf { it in 1..9 }
            ?: if (styleName.equals("title", ignoreCase = true)) 1 else if (styleName.equals("subtitle", ignoreCase = true)) 2 else null
        val lowerName = styleName.lowercase()
        return when {
            headingLevel != null -> Block(BlockType.HEADING, text, headingLevel.coerceIn(1, 6))
            props?.getElementsByTag("w:numpr")?.isNotEmpty() == true || lowerName.startsWith("list") && lowerName != "list paragraph" ->
                Block(BlockType.LIST_ITEM, text, props?.getElementsByTag("w:ilvl")?.firstOrNull()?.attr("w:val")?.toIntOrNull() ?: 0, spans)
            lowerName.contains("quote") || lowerName == "block text" -> Block(BlockType.QUOTE, text, 0, spans)
            lowerName.contains("code") || lowerName.contains("preformatted") -> Block(BlockType.CODE, text)
            else -> Block(BlockType.PARAGRAPH, text, 0, spans)
        }
    }

    private fun collectRuns(parent: Element, builder: RunTextBuilder) {
        for (child in parent.children()) {
            when (child.normalName()) {
                "w:r" -> run(child, builder)
                "w:hyperlink", "w:ins", "w:smarttag", "w:sdt", "w:sdtcontent", "w:fldsimple", "w:customxml" -> collectRuns(child, builder)
            }
        }
    }

    private fun run(r: Element, builder: RunTextBuilder) {
        val props = r.children().firstOrNull { it.normalName() == "w:rpr" }
        val bold = props?.getElementsByTag("w:b")?.firstOrNull()?.let(::isOn) ?: false
        val italic = props?.getElementsByTag("w:i")?.firstOrNull()?.let(::isOn) ?: false
        val font = props?.getElementsByTag("w:rfonts")?.firstOrNull()?.attr("w:ascii")?.lowercase().orEmpty()
        val mono = font.isNotEmpty() && MONO_FONTS.any { it in font }
        val text = StringBuilder()
        for (node in r.children()) {
            when (node.normalName()) {
                "w:t" -> text.append(node.wholeText())
                "w:tab" -> text.append(' ')
                "w:br", "w:cr" -> if (node.attr("w:type") != "page") text.append(' ')
                "w:nobreakhyphen" -> text.append('-')
            }
        }
        builder.append(text.toString(), bold, italic, mono)
    }

    /** `<w:b/>` включено, если нет `w:val="0"` / `"false"`. */
    private fun isOn(el: Element): Boolean = el.attr("w:val").lowercase() !in setOf("0", "false", "off")

    /** Копит текст абзаца из запусков, схлопывая пробелы и запоминая диапазоны выделений. */
    internal class RunTextBuilder {
        private val text = StringBuilder()
        private val raw = ArrayList<Span>()

        fun append(piece: String, bold: Boolean, italic: Boolean, mono: Boolean) {
            val start = text.length
            var previousSpace = text.isEmpty() || text.last() == ' '
            for (c in piece) {
                val space = c.isWhitespace()
                if (space && previousSpace) continue
                text.append(if (space) ' ' else c)
                previousSpace = space
            }
            if (text.length == start) return
            if (bold) raw.add(Span(start, text.length, SpanStyleType.BOLD))
            if (italic) raw.add(Span(start, text.length, SpanStyleType.ITALIC))
            if (mono) raw.add(Span(start, text.length, SpanStyleType.CODE))
        }

        fun build(): Pair<String, List<Span>> {
            val full = text.toString()
            val lead = full.length - full.trimStart().length
            val trimmed = full.trim()
            val spans = raw.mapNotNull { s ->
                val a = (s.start - lead).coerceIn(0, trimmed.length)
                val b = (s.end - lead).coerceIn(a, trimmed.length)
                if (b > a) Span(a, b, s.style) else null
            }
            return trimmed to mergeSpans(spans)
        }

        private fun mergeSpans(spans: List<Span>): List<Span> {
            val result = ArrayList<Span>()
            for (s in spans.sortedWith(compareBy({ it.style }, { it.start }))) {
                val last = result.lastOrNull()
                if (last != null && last.style == s.style && s.start <= last.end) {
                    result[result.lastIndex] = Span(last.start, maxOf(last.end, s.end), s.style)
                } else result.add(s)
            }
            return result.sortedBy { it.start }
        }
    }
}
