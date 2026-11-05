package com.localdoc.ai.book.export

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book

/**
 * OpenDocument `.odt`: `text:h` с уровнями, вложенные `text:list`, стили «Quotations» и
 * «Preformatted Text», выделения через автоматические стили, картинки в `Pictures/`,
 * миниатюра обложки в `Thumbnails/thumbnail.png` (если обложка — PNG).
 */
internal object OdtWriter {

    private const val NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
        "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
        "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
        "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
        "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" " +
        "xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" " +
        "xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""

    private const val MAX_WIDTH_CM = 16.0
    private const val MAX_HEIGHT_CM = 22.0
    private const val CM_PER_PIXEL = 0.0265

    fun write(book: Book): ByteArray {
        val images = book.exportImages()
        val used = book.blocks.mapNotNull { it.image }.filter { it in images }.distinct()

        val content = StringBuilder()
        var frame = 1
        for (block in book.blocks) {
            when (block.type) {
                BlockType.HEADING -> content.append("<text:h text:outline-level=\"${block.level.coerceIn(1, 6)}\">").append(spans(block)).append("</text:h>\n")
                BlockType.PARAGRAPH -> content.append("<text:p text:style-name=\"Standard\">").append(spans(block)).append("</text:p>\n")
                BlockType.QUOTE -> content.append("<text:p text:style-name=\"Quotations\">").append(spans(block)).append("</text:p>\n")
                BlockType.CODE -> content.append("<text:p text:style-name=\"Preformatted_20_Text\">").append(codeText(block.text)).append("</text:p>\n")
                BlockType.LIST_ITEM -> {
                    val depth = block.level.coerceIn(0, 8) + 1
                    content.append("<text:list>".repeat(depth)).append("<text:list-item><text:p text:style-name=\"Standard\">")
                        .append(spans(block)).append("</text:p></text:list-item>").append("</text:list>".repeat(depth)).append('\n')
                }
                BlockType.IMAGE -> block.image?.let { images[it] }?.let { content.append(imageParagraph(it, block.text, frame++)) }
            }
        }

        val zip = ZipWriter()
        zip.add("mimetype", "application/vnd.oasis.opendocument.text")
        zip.add("META-INF/manifest.xml", manifest(used.mapNotNull { images[it] }, book.cover?.let { images[it] }?.takeIf { it.mime == "image/png" }))
        zip.add(
            "content.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-content $NS office:version=\"1.2\">" +
                "<office:automatic-styles>" +
                "<style:style style:name=\"B\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\"/></style:style>" +
                "<style:style style:name=\"I\" style:family=\"text\"><style:text-properties fo:font-style=\"italic\"/></style:style>" +
                "<style:style style:name=\"BI\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\" fo:font-style=\"italic\"/></style:style>" +
                "<style:style style:name=\"C\" style:family=\"text\"><style:text-properties style:font-name=\"Courier New\" fo:font-family=\"'Courier New'\"/></style:style>" +
                "</office:automatic-styles><office:body><office:text>\n$content</office:text></office:body></office:document-content>"
        )
        zip.add(
            "styles.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-styles $NS office:version=\"1.2\"><office:styles>" +
                "<style:style style:name=\"Standard\" style:family=\"paragraph\"/>" +
                "<style:style style:name=\"Quotations\" style:family=\"paragraph\"><style:paragraph-properties fo:margin-left=\"1cm\"/><style:text-properties fo:font-style=\"italic\"/></style:style>" +
                "<style:style style:name=\"Preformatted_20_Text\" style:display-name=\"Preformatted Text\" style:family=\"paragraph\"><style:text-properties style:font-name=\"Courier New\" fo:font-family=\"'Courier New'\"/></style:style>" +
                "</office:styles></office:document-styles>"
        )
        zip.add(
            "meta.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-meta $NS office:version=\"1.2\"><office:meta>" +
                "<dc:title>${xmlEscape(book.title)}</dc:title><dc:creator>${xmlEscape(book.author)}</dc:creator></office:meta></office:document-meta>"
        )
        used.mapNotNull { images[it] }.forEach { zip.add("Pictures/${it.id}.${it.extension}", it.bytes) }
        book.cover?.let { images[it] }?.takeIf { it.mime == "image/png" }?.let { zip.add("Thumbnails/thumbnail.png", it.bytes) }
        return zip.finish()
    }

    private fun spans(block: Block): String = block.runs().joinToString("") { run ->
        val style = when {
            run.code -> "C"
            run.bold && run.italic -> "BI"
            run.bold -> "B"
            run.italic -> "I"
            else -> null
        }
        val text = textContent(run.text)
        if (style == null) text else "<text:span text:style-name=\"$style\">$text</text:span>"
    }

    /** Пробелы подряд и переводы строк в ODF записываются особыми элементами. */
    private fun textContent(s: String): String {
        val out = StringBuilder()
        var spaces = 0
        for (c in s) {
            if (c == ' ') { spaces++; continue }
            if (spaces > 0) { out.append(spaceRun(spaces)); spaces = 0 }
            when (c) {
                '\n' -> out.append("<text:line-break/>")
                '\t' -> out.append("<text:tab/>")
                else -> out.append(xmlEscape(c.toString()))
            }
        }
        if (spaces > 0) out.append(spaceRun(spaces))
        return out.toString()
    }

    private fun spaceRun(n: Int) = if (n == 1) " " else " <text:s text:c=\"${n - 1}\"/>"

    private fun codeText(s: String) = textContent(s)

    private fun imageParagraph(image: ExportImage, alt: String, n: Int): String {
        val size = image.size
        var w = (size?.width ?: 600) * CM_PER_PIXEL
        var h = (size?.height ?: 400) * CM_PER_PIXEL
        val scale = minOf(1.0, MAX_WIDTH_CM / w, MAX_HEIGHT_CM / h)
        w *= scale
        h *= scale
        fun cm(v: Double) = ((v * 100).toLong() / 100.0).toString() + "cm"
        return "<text:p text:style-name=\"Standard\"><draw:frame draw:name=\"img$n\" text:anchor-type=\"as-char\" svg:width=\"${cm(w)}\" svg:height=\"${cm(h)}\">" +
            "<draw:image xlink:href=\"Pictures/${image.id}.${image.extension}\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>" +
            "<svg:title>${xmlEscape(alt)}</svg:title></draw:frame></text:p>\n"
    }

    private fun manifest(images: List<ExportImage>, thumbnail: ExportImage?): String {
        val pics = images.joinToString("") { "<manifest:file-entry manifest:full-path=\"Pictures/${it.id}.${it.extension}\" manifest:media-type=\"${it.mime}\"/>" }
        val thumb = if (thumbnail != null) "<manifest:file-entry manifest:full-path=\"Thumbnails/thumbnail.png\" manifest:media-type=\"image/png\"/>" else ""
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.2\">" +
            "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>" +
            "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>" +
            "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>" +
            "<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>$pics$thumb</manifest:manifest>"
    }
}
