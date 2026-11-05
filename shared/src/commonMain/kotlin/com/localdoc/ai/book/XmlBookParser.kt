package com.localdoc.ai.book

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.parser.Parser
import kotlin.io.encoding.Base64

/**
 * XML-документы. Узнаём: собственный формат LocalDoc (`<book xmlns="urn:localdoc:book:1">`, его пишет
 * экспорт «Сохранить как → XML»), FictionBook и XHTML, Word 2003 XML (`w:p`/`w:t`), а для любого другого
 * XML (DocBook, TEI и т. п.) — обход по именам элементов: заголовки, списки, цитаты, код, абзацы.
 */
object XmlBookParser {

    const val NAMESPACE = "urn:localdoc:book:1"

    private val HEADING_TAGS = setOf("title", "heading", "head", "subtitle", "titel", "caption")
    private val ITEM_TAGS = setOf("li", "item", "listitem", "entry")
    private val QUOTE_TAGS = setOf("blockquote", "quote", "epigraph", "cite", "q")
    private val CODE_TAGS = setOf("pre", "code", "programlisting", "screen", "listing")
    private val PARAGRAPH_TAGS = setOf("p", "para", "par", "line", "text", "paragraph", "simpara")
    private val INLINE_TAGS = setOf("b", "i", "em", "strong", "span", "a", "sup", "sub", "u", "emphasis", "link", "ulink", "code", "tt")
    private val SKIP_TAGS = setOf("binary", "script", "style", "metadata", "meta", "info", "bookinfo", "teiheader", "resources")

    fun parse(bytes: ByteArray, fallbackTitle: String): Book {
        val xml = TextDecoder.decode(bytes, TextDecoder.xmlDeclaredEncoding(bytes))
        val root = try {
            Ksoup.parse(xml, Parser.xmlParser(), "").children().firstOrNull()
        } catch (e: Exception) {
            throw IllegalArgumentException("Повреждённый XML: ${e.message ?: "не удалось разобрать"}")
        } ?: throw IllegalArgumentException("Пустой XML-документ")

        return when {
            root.normalName() == "fictionbook" -> Fb2Parser.parse(bytes, fallbackTitle)
            root.normalName() == "html" -> HtmlFileParser.parse(xml, fallbackTitle, "")
            root.normalName() == "book" && root.attr("xmlns") == NAMESPACE -> parseLocalDoc(root, fallbackTitle)
            root.getElementsByTag("w:p").isNotEmpty() -> parseWordXml(root, fallbackTitle)
            else -> parseGeneric(root, fallbackTitle)
        }.also { require(it.blocks.isNotEmpty()) { "В XML-документе не найден текст" } }
    }

    // ---------- собственный формат ----------

    private fun parseLocalDoc(root: Element, fallbackTitle: String): Book {
        val images = ImageCollector()
        val idMap = HashMap<String, String>()
        root.getElementsByTag("resources").firstOrNull()?.children()?.forEach { res ->
            if (res.normalName() != "image") return@forEach
            val data = runCatching { Base64.decode(res.text().filterNot(Char::isWhitespace)) }.getOrNull() ?: return@forEach
            images.add(data)?.let { idMap[res.attr("id")] = it }
        }
        val blocks = ArrayList<Block>()
        for (el in root.children()) {
            when (el.normalName()) {
                "h" -> inlineBlock(el, BlockType.HEADING, (el.attr("level").toIntOrNull() ?: 1).coerceIn(1, 6))?.let(blocks::add)
                "p" -> inlineBlock(el, BlockType.PARAGRAPH)?.let(blocks::add)
                "li" -> inlineBlock(el, BlockType.LIST_ITEM, el.attr("level").toIntOrNull() ?: 0)?.let(blocks::add)
                "quote" -> inlineBlock(el, BlockType.QUOTE)?.let(blocks::add)
                "pre" -> el.text().takeIf { it.isNotBlank() }?.let { blocks.add(Block(BlockType.CODE, el.wholeText().trimEnd())) }
                "img" -> {
                    val ref = el.attr("ref")
                    val id = idMap[ref] ?: ref.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    if (id != null) blocks.add(Block(BlockType.IMAGE, el.attr("alt"), image = id))
                }
            }
        }
        val chapterStarts = root.getElementsByTag("toc").firstOrNull()?.children()
            ?.mapNotNull { c -> c.attr("block").toIntOrNull()?.let { Chapter(c.text().trim(), it) } }
            .orEmpty()
        return Book(
            title = root.attr("title").ifBlank { fallbackTitle },
            author = root.attr("author"),
            format = BookFormat.XML,
            blocks = blocks,
            chapters = chapterStarts.ifEmpty { chaptersFromHeadings(blocks) }
        ).withImages(images, cover = root.attr("cover").takeIf { it.isNotBlank() }?.let { idMap[it] ?: it.takeIf { u -> u.startsWith("http") } })
    }

    /** Блок с инлайн-выделениями `<b>`, `<i>`, `<code>`. */
    private fun inlineBlock(el: Element, type: BlockType, level: Int = 0): Block? {
        val builder = DocxParser.RunTextBuilder()
        fun walk(node: Element, bold: Boolean, italic: Boolean, code: Boolean) {
            for (child in node.childNodes()) {
                when (child) {
                    is TextNode -> builder.append(child.getWholeText(), bold, italic, code)
                    is Element -> walk(
                        child,
                        bold || child.normalName() == "b",
                        italic || child.normalName() == "i",
                        code || child.normalName() == "code"
                    )
                    else -> Unit
                }
            }
        }
        walk(el, bold = false, italic = false, code = false)
        val (text, spans) = builder.build()
        return if (text.isBlank()) null else Block(type, text, level, spans)
    }

    // ---------- Word 2003 XML ----------

    private fun parseWordXml(root: Element, fallbackTitle: String): Book {
        val blocks = root.getElementsByTag("w:p").mapNotNull { p ->
            val text = HtmlBlockBuilder.normalizeSpaces(p.getElementsByTag("w:t").joinToString("") { it.wholeText() })
            val style = p.getElementsByTag("w:pstyle").firstOrNull()?.attr("w:val").orEmpty()
            val level = Regex("(?i)^(?:heading|заголовок)\\s*(\\d)$").find(style)?.groupValues?.get(1)?.toIntOrNull()
            if (text.isBlank()) null else if (level != null) Block(BlockType.HEADING, text, level.coerceIn(1, 6)) else Block(BlockType.PARAGRAPH, text)
        }
        return Book(title = fallbackTitle, format = BookFormat.XML, blocks = blocks, chapters = chaptersFromHeadings(blocks))
    }

    // ---------- произвольный XML ----------

    private fun parseGeneric(root: Element, fallbackTitle: String): Book {
        val blocks = ArrayList<Block>()
        var docTitle = ""
        val author = (root.getElementsByTag("author").firstOrNull() ?: root.getElementsByTag("creator").firstOrNull())
            ?.text()?.let(HtmlBlockBuilder::normalizeSpaces).orEmpty()

        fun walk(el: Element, depth: Int) {
            val tag = el.normalName().substringAfter(':')
            when {
                tag in SKIP_TAGS || tag == "author" || tag == "creator" -> Unit
                tag in HEADING_TAGS -> {
                    val text = HtmlBlockBuilder.normalizeSpaces(el.text())
                    if (text.isEmpty()) return
                    if (docTitle.isEmpty() && depth <= 1 && blocks.isEmpty()) docTitle = text
                    blocks.add(Block(BlockType.HEADING, text, (depth).coerceIn(1, 6)))
                }
                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' ->
                    blocks.add(Block(BlockType.HEADING, HtmlBlockBuilder.normalizeSpaces(el.text()), tag[1] - '0'))
                tag in ITEM_TAGS && el.children().none { it.normalName() !in INLINE_TAGS && it.normalName() !in PARAGRAPH_TAGS } ->
                    HtmlBlockBuilder.normalizeSpaces(el.text()).takeIf { it.isNotEmpty() }?.let { blocks.add(Block(BlockType.LIST_ITEM, it)) }
                tag in QUOTE_TAGS ->
                    HtmlBlockBuilder.normalizeSpaces(el.text()).takeIf { it.isNotEmpty() }?.let { blocks.add(Block(BlockType.QUOTE, it)) }
                tag in CODE_TAGS && el.children().isEmpty() ->
                    el.wholeText().trimEnd().takeIf { it.isNotBlank() }?.let { blocks.add(Block(BlockType.CODE, it)) }
                tag in PARAGRAPH_TAGS || el.children().all { it.normalName().substringAfter(':') in INLINE_TAGS } ->
                    HtmlBlockBuilder.normalizeSpaces(el.text()).takeIf { it.isNotEmpty() }?.let { blocks.add(Block(BlockType.PARAGRAPH, it)) }
                else -> for (node: Node in el.childNodes()) {
                    when (node) {
                        is TextNode -> HtmlBlockBuilder.normalizeSpaces(node.getWholeText()).takeIf { it.isNotEmpty() }
                            ?.let { blocks.add(Block(BlockType.PARAGRAPH, it)) }
                        is Element -> walk(node, depth + 1)
                        else -> Unit
                    }
                }
            }
        }
        walk(root, 0)
        return Book(
            title = docTitle.ifBlank { fallbackTitle },
            author = author,
            format = BookFormat.XML,
            blocks = blocks,
            chapters = chaptersFromHeadings(blocks)
        )
    }
}
