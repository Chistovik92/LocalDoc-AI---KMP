package com.localdoc.ai.book.export

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.MarkdownParser
import com.localdoc.ai.book.XmlBookParser
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Текстовые форматы: TXT, Markdown, HTML и собственный XML. */
@OptIn(ExperimentalEncodingApi::class)
internal object TextWriters {

    private fun dataUri(image: ExportImage) = "data:${image.mime};base64,${Base64.encode(image.bytes)}"

    /** Адрес картинки для текстовых форматов: внешняя ссылка как есть, своя — data:-URI. */
    private fun imageUrl(ref: String, images: Map<String, ExportImage>): String? =
        if (ref.isRemoteImage()) ref else images[ref]?.let(::dataUri)

    // ---------- TXT ----------

    fun txt(book: Book): ByteArray {
        val out = StringBuilder()
        out.append(book.title).append('\n')
        if (book.author.isNotBlank()) out.append(book.author).append('\n')
        out.append('\n')
        for (block in book.blocks) {
            when (block.type) {
                BlockType.IMAGE -> if (block.text.isNotBlank()) out.append("[Рисунок: ").append(block.text).append("]\n\n")
                BlockType.LIST_ITEM -> out.append("  ".repeat(block.level.coerceIn(0, 8))).append("• ").append(block.text).append("\n\n")
                BlockType.HEADING -> out.append('\n').append(block.text).append("\n\n")
                else -> out.append(block.text).append("\n\n")
            }
        }
        return (out.toString().trimEnd() + "\n").encodeToByteArray()
    }

    // ---------- Markdown ----------

    private fun markdownInline(block: Block): String = block.runs().joinToString("") { run ->
        var s = run.text
        if (run.code) s = "`$s`"
        if (run.italic) s = "*$s*"
        if (run.bold) s = "**$s**"
        s
    }

    fun markdown(book: Book): ByteArray {
        val images = book.exportImages()
        val out = StringBuilder()
        book.cover?.let { c -> imageUrl(c, images)?.let { out.append("![").append(MarkdownParser.COVER_ALT).append("](").append(it).append(")\n\n") } }
        val hasTitleHeading = book.blocks.firstOrNull()?.let { it.type == BlockType.HEADING && it.level == 1 } == true
        if (!hasTitleHeading) out.append("# ").append(book.title).append("\n\n")
        for (block in book.blocks) {
            when (block.type) {
                BlockType.HEADING -> out.append("#".repeat(block.level.coerceIn(1, 6))).append(' ').append(markdownInline(block)).append("\n\n")
                BlockType.PARAGRAPH -> out.append(markdownInline(block)).append("\n\n")
                BlockType.QUOTE -> out.append("> ").append(markdownInline(block).replace("\n", "\n> ")).append("\n\n")
                BlockType.CODE -> out.append("```\n").append(block.text).append("\n```\n\n")
                BlockType.LIST_ITEM -> out.append("  ".repeat(block.level.coerceIn(0, 8))).append("- ").append(markdownInline(block)).append("\n\n")
                BlockType.IMAGE -> block.image?.let { imageUrl(it, images) }?.let {
                    out.append("![").append(block.text.replace("]", "")).append("](").append(it).append(")\n\n")
                }
            }
        }
        return (out.toString().trimEnd() + "\n").encodeToByteArray()
    }

    // ---------- HTML ----------

    fun html(book: Book): ByteArray {
        val images = book.exportImages()
        val out = StringBuilder()
        out.append("<!DOCTYPE html>\n<html lang=\"").append(book.guessLanguage()).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<title>").append(xmlEscape(book.title)).append("</title>\n")
        if (book.author.isNotBlank()) out.append("<meta name=\"author\" content=\"").append(xmlEscape(book.author)).append("\">\n")
        book.cover?.let { c ->
            imageUrl(c, images)?.let { out.append("<meta property=\"og:image\" content=\"").append(xmlEscape(it)).append("\">\n") }
        }
        out.append("<style>body{max-width:42em;margin:2em auto;padding:0 1em;font:1.1em/1.6 Georgia,serif}")
        out.append("img{max-width:100%;height:auto}pre{white-space:pre-wrap;background:#f4f4f4;padding:.6em}")
        out.append("blockquote{margin-left:1em;color:#555}ul{margin:0;padding-left:1.4em}</style>\n</head>\n<body>\n<article>\n")
        out.append(HtmlRender.blocks(book.blocks) { ref -> imageUrl(ref, images) })
        out.append("</article>\n</body>\n</html>\n")
        return out.toString().encodeToByteArray()
    }

    // ---------- XML ----------

    private fun xmlInline(block: Block): String = HtmlRender.inline(block)

    fun xml(book: Book): ByteArray {
        val images = book.exportImages()
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<book xmlns=\"").append(XmlBookParser.NAMESPACE).append("\" title=\"")
            .append(xmlEscape(book.title)).append("\" author=\"").append(xmlEscape(book.author)).append('"')
        book.cover?.let { out.append(" cover=\"").append(xmlEscape(it)).append('"') }
        out.append(">\n")
        for (block in book.blocks) {
            when (block.type) {
                BlockType.HEADING -> out.append("<h level=\"").append(block.level.coerceIn(1, 6)).append("\">").append(xmlInline(block)).append("</h>\n")
                BlockType.PARAGRAPH -> out.append("<p>").append(xmlInline(block)).append("</p>\n")
                BlockType.QUOTE -> out.append("<quote>").append(xmlInline(block)).append("</quote>\n")
                BlockType.CODE -> out.append("<pre>").append(xmlEscape(block.text)).append("</pre>\n")
                BlockType.LIST_ITEM -> out.append("<li level=\"").append(block.level.coerceIn(0, 8)).append("\">").append(xmlInline(block)).append("</li>\n")
                BlockType.IMAGE -> block.image?.let {
                    out.append("<img ref=\"").append(xmlEscape(it)).append("\" alt=\"").append(xmlEscape(block.text)).append("\"/>\n")
                }
            }
        }
        if (book.chapters.isNotEmpty()) {
            out.append("<toc>\n")
            book.chapters.forEach {
                out.append("<chapter block=\"").append(it.startBlock).append("\">").append(xmlEscape(it.title)).append("</chapter>\n")
            }
            out.append("</toc>\n")
        }
        val used = (book.blocks.mapNotNull { it.image } + listOfNotNull(book.cover)).toSet()
        val local = images.values.filter { it.id in used }
        if (local.isNotEmpty()) {
            out.append("<resources>\n")
            local.forEach {
                out.append("<image id=\"").append(it.id).append("\" type=\"").append(it.mime).append("\">")
                    .append(Base64.encode(it.bytes)).append("</image>\n")
            }
            out.append("</resources>\n")
        }
        out.append("</book>\n")
        return out.toString().encodeToByteArray()
    }
}
