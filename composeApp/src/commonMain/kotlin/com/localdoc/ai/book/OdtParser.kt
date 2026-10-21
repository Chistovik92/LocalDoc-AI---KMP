package com.localdoc.ai.book

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.parser.Parser

/**
 * Текстовый документ OpenDocument (`.odt`, OpenOffice / LibreOffice): ZIP → `content.xml`.
 * Абзацы, заголовки (`text:h`), списки, таблицы (строка → абзац), жирный/курсив по стилям.
 */
object OdtParser {

    private const val CONTENT = "content.xml"
    private val MONO_FONTS = listOf("courier", "mono", "consolas", "menlo")
    private val MIMETYPE = "application/vnd.oasis.opendocument.text"

    fun isOdt(zip: ZipArchive): Boolean =
        zip.contains(CONTENT) && zip.readText("mimetype")?.trim()?.startsWith(MIMETYPE) == true

    fun parse(zip: ZipArchive, fallbackTitle: String): Book {
        val xml = zip.readText(CONTENT) ?: throw IllegalArgumentException("Это не документ OpenDocument (.odt): нет content.xml")
        val doc = Ksoup.parse(xml, Parser.xmlParser(), "")
        val styles = readStyles(doc.getElementsByTag("office:automatic-styles").firstOrNull())
        val text = doc.getElementsByTag("office:text").firstOrNull()
            ?: throw IllegalArgumentException("Повреждённый .odt: в документе нет текста")

        val blocks = ArrayList<Block>()
        val images = ImageCollector()
        walk(text, styles, 0, blocks, images, zip)

        val meta = zip.readText("meta.xml")?.let { Ksoup.parse(it, Parser.xmlParser(), "") }
        val title = meta?.getElementsByTag("dc:title")?.firstOrNull()?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = meta?.getElementsByTag("dc:creator")?.firstOrNull()?.text()?.trim().orEmpty()
        val thumbnail = runCatching { zip.read("Thumbnails/thumbnail.png") }.getOrNull()?.let(images::add)
        return Book(title = title, author = author, format = BookFormat.ODT, blocks = blocks, chapters = chaptersFromHeadings(blocks))
            .withImages(images, thumbnail)
    }

    /** Стиль текста: жирный / курсив из `style:text-properties`. */
    private data class TextStyle(val bold: Boolean, val italic: Boolean, val mono: Boolean = false)

    private fun readStyles(root: Element?): Map<String, TextStyle> {
        root ?: return emptyMap()
        return root.getElementsByTag("style:style").associate { style ->
            val props = style.getElementsByTag("style:text-properties").firstOrNull()
            style.attr("style:name") to TextStyle(
                bold = props?.attr("fo:font-weight") == "bold",
                italic = props?.attr("fo:font-style") == "italic",
                mono = (props?.attr("style:font-name").orEmpty() + props?.attr("fo:font-family").orEmpty()).lowercase()
                    .let { f -> MONO_FONTS.any { it in f } }
            )
        }
    }

    private fun walk(
        parent: Element,
        styles: Map<String, TextStyle>,
        listLevel: Int,
        out: MutableList<Block>,
        images: ImageCollector,
        zip: ZipArchive
    ) {
        for (child in parent.children()) {
            when (child.normalName()) {
                "text:h" -> {
                    block(child, styles, out) { text, spans ->
                        Block(BlockType.HEADING, text, (child.attr("text:outline-level").toIntOrNull() ?: 1).coerceIn(1, 6), spans)
                    }
                    out.addAll(paragraphImages(child, images, zip))
                }
                "text:p" -> {
                    block(child, styles, out) { text, spans ->
                    val style = child.attr("text:style-name").lowercase()
                    when {
                        listLevel > 0 -> Block(BlockType.LIST_ITEM, text, listLevel - 1, spans)
                        "quotation" in style -> Block(BlockType.QUOTE, text, 0, spans)
                        "preformatted" in style || "source" in style -> Block(BlockType.CODE, text)
                        "title" == style -> Block(BlockType.HEADING, text, 1, spans)
                        else -> Block(BlockType.PARAGRAPH, text, 0, spans)
                    }
                    }
                    out.addAll(paragraphImages(child, images, zip))
                }
                "text:list" -> walk(child, styles, listLevel + 1, out, images, zip)
                "text:list-item", "text:list-header", "text:section", "office:text" -> walk(child, styles, listLevel, out, images, zip)
                "table:table" -> table(child, styles, out, images, zip)
            }
        }
    }

    private inline fun block(
        el: Element,
        styles: Map<String, TextStyle>,
        out: MutableList<Block>,
        make: (String, List<Span>) -> Block
    ) {
        val builder = DocxParser.RunTextBuilder()
        inline(el, styles, builder, TextStyle(false, false))
        val (text, spans) = builder.build()
        if (text.isNotBlank()) out.add(make(text, spans))
    }

    /** `draw:image xlink:href="Pictures/…"` внутри абзаца или рамки. */
    private fun paragraphImages(p: Element, images: ImageCollector, zip: ZipArchive): List<Block> =
        p.getElementsByTag("draw:image").mapNotNull { img ->
            val href = img.attr("xlink:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ref = zipImage(zip, "", href, images) ?: return@mapNotNull null
            val alt = img.parent()?.getElementsByTag("svg:title")?.firstOrNull()?.text()?.trim().orEmpty()
            Block(BlockType.IMAGE, alt, image = ref)
        }

    private fun table(table: Element, styles: Map<String, TextStyle>, out: MutableList<Block>, images: ImageCollector, zip: ZipArchive) {
        for (row in table.getElementsByTag("table:table-row")) {
            val cells = row.children().filter { it.normalName() == "table:table-cell" }.mapNotNull { cell ->
                val cellBlocks = ArrayList<Block>()
                walk(cell, styles, 0, cellBlocks, images, zip)
                cellBlocks.filterNot { it.isImage }.joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }
            }
            if (cells.isNotEmpty()) out.add(Block(BlockType.PARAGRAPH, cells.joinToString(" | ")))
        }
    }

    /** Обход содержимого абзаца: текст, `text:span` со стилем, пробелы `text:s`, табуляции и переносы. */
    private fun inline(parent: Element, styles: Map<String, TextStyle>, builder: DocxParser.RunTextBuilder, current: TextStyle) {
        for (node: Node in parent.childNodes()) {
            when (node) {
                is TextNode -> builder.append(node.getWholeText(), current.bold, current.italic, current.mono)
                is Element -> when (node.normalName()) {
                    "text:span", "text:a" -> {
                        val own = styles[node.attr("text:style-name")]
                        inline(node, styles, builder, TextStyle(current.bold || own?.bold == true, current.italic || own?.italic == true, current.mono || own?.mono == true))
                    }
                    "text:s", "text:tab", "text:line-break" -> builder.append(" ", false, false, false)
                    "text:note", "text:bookmark", "text:bookmark-start", "text:bookmark-end",
                    "draw:frame", "draw:image", "svg:title", "svg:desc", "draw:object" -> Unit
                    else -> inline(node, styles, builder, current)
                }
            }
        }
    }
}
