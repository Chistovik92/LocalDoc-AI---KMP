package com.localdoc.ai.book.export

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * FictionBook 2.0: описание (автор, название, обложка), тело с секциями по главам, картинки в
 * `<binary>` (FB2 допускает только PNG и JPEG — остальные пропускаются), внешние ссылки на картинки
 * сохраняются подписью.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object Fb2Writer {

    fun write(book: Book): ByteArray {
        val images = book.exportImages().filterValues { it.mime == "image/png" || it.mime == "image/jpeg" }
        fun binaryId(image: ExportImage) = "${image.id}.${image.extension}"
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">\n")
        out.append("<description><title-info>\n<genre>prose</genre>\n")
        if (book.author.isNotBlank()) {
            val parts = book.author.trim().split(Regex("\\s+"))
            out.append("<author>")
            when (parts.size) {
                1 -> out.append("<nickname>").append(xmlEscape(parts[0])).append("</nickname>")
                2 -> out.append("<first-name>").append(xmlEscape(parts[0])).append("</first-name><last-name>").append(xmlEscape(parts[1])).append("</last-name>")
                else -> out.append("<first-name>").append(xmlEscape(parts.first())).append("</first-name><middle-name>")
                    .append(xmlEscape(parts.subList(1, parts.size - 1).joinToString(" "))).append("</middle-name><last-name>")
                    .append(xmlEscape(parts.last())).append("</last-name>")
            }
            out.append("</author>\n")
        }
        out.append("<book-title>").append(xmlEscape(book.title)).append("</book-title>\n")
        out.append("<lang>").append(book.guessLanguage()).append("</lang>\n")
        book.cover?.let { images[it] }?.let { out.append("<coverpage><image l:href=\"#").append(binaryId(it)).append("\"/></coverpage>\n") }
        out.append("</title-info><document-info><author><nickname>LocalDoc AI</nickname></author><id>").append(book.stableId())
            .append("</id><version>1.0</version></document-info></description>\n")

        out.append("<body>\n")
        val starts = book.chapters.map { it.startBlock }.toSet()
        var open = false
        for ((index, block) in book.blocks.withIndex()) {
            if (index in starts || (index == 0 && !open)) {
                if (open) out.append("</section>\n")
                out.append("<section>\n")
                open = true
                if (index in starts && block.type == BlockType.HEADING) {
                    out.append("<title><p>").append(xmlEscape(block.text)).append("</p></title>\n")
                    continue
                }
            }
            writeBlock(out, block, images, ::binaryId)
        }
        if (open) out.append("</section>\n")
        out.append("</body>\n")

        val used = (book.blocks.mapNotNull { it.image } + listOfNotNull(book.cover)).toSet()
        images.values.filter { it.id in used }.forEach {
            out.append("<binary id=\"").append(binaryId(it)).append("\" content-type=\"").append(it.mime).append("\">")
                .append(Base64.Mime.encode(it.bytes)).append("</binary>\n")
        }
        out.append("</FictionBook>\n")
        return out.toString().encodeToByteArray()
    }

    private fun inline(block: Block): String = block.runs().joinToString("") { run ->
        var s = xmlEscape(run.text)
        if (run.code) s = "<code>$s</code>"
        if (run.italic) s = "<emphasis>$s</emphasis>"
        if (run.bold) s = "<strong>$s</strong>"
        s
    }

    private fun writeBlock(out: StringBuilder, block: Block, images: Map<String, ExportImage>, binaryId: (ExportImage) -> String) {
        when (block.type) {
            BlockType.HEADING -> out.append("<subtitle>").append(xmlEscape(block.text)).append("</subtitle>\n")
            BlockType.PARAGRAPH -> out.append("<p>").append(inline(block)).append("</p>\n")
            BlockType.QUOTE -> out.append("<cite><p>").append(inline(block)).append("</p></cite>\n")
            BlockType.CODE -> block.text.lines().forEach { out.append("<p><code>").append(xmlEscape(it)).append("</code></p>\n") }
            BlockType.LIST_ITEM -> out.append("<p>").append("  ".repeat(block.level.coerceIn(0, 8))).append("• ").append(inline(block)).append("</p>\n")
            BlockType.IMAGE -> {
                val image = block.image?.let { images[it] }
                if (image != null) out.append("<image l:href=\"#").append(binaryId(image)).append("\"/>\n")
                else if (block.text.isNotBlank()) out.append("<p><emphasis>").append(xmlEscape(block.text)).append("</emphasis></p>\n")
            }
        }
    }
}
