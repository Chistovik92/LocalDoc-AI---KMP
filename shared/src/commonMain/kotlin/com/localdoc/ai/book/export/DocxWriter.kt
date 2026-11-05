package com.localdoc.ai.book.export

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book

/**
 * Word `.docx` (Office Open XML): стили «Heading 1–6», «Quote», «Code», маркированные списки через
 * `numbering.xml`, выделения, картинки (`w:drawing`) и миниатюра обложки в `docProps`.
 */
internal object DocxWriter {

    private const val EMU_PER_PIXEL = 9525L
    private const val MAX_WIDTH_EMU = 5_760_000L // ~16 см
    private const val MAX_HEIGHT_EMU = 8_000_000L
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun write(book: Book): ByteArray {
        val images = book.exportImages()
        val used = (book.blocks.mapNotNull { it.image }.filter { it in images }).distinct()
        val relIds = used.withIndex().associate { (i, id) -> id to "rId${100 + i}" }

        val body = StringBuilder()
        var picId = 1
        for (block in book.blocks) {
            when (block.type) {
                BlockType.IMAGE -> {
                    val image = block.image?.let { images[it] }
                    if (image != null) body.append(imageParagraph(image, relIds.getValue(image.id), block.text, picId++))
                }
                else -> body.append(paragraph(block))
            }
        }
        body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")

        val zip = ZipWriter()
        val cover = book.cover?.let { images[it] }
        val thumbExt = cover?.takeIf { it.mime == "image/jpeg" || it.mime == "image/png" }?.let { if (it.mime == "image/png") "png" else "jpeg" }
        zip.add("[Content_Types].xml", contentTypes(used.mapNotNull { images[it]?.extension }.toSet(), thumbExt))
        zip.add("_rels/.rels", rootRels(thumbExt))
        zip.add("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:document xmlns:w=\"$W_NS\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>$body</w:body></w:document>")
        zip.add("word/styles.xml", styles())
        zip.add("word/numbering.xml", numbering())
        zip.add("word/_rels/document.xml.rels", documentRels(used.mapNotNull { images[it] }, relIds))
        zip.add(
            "docProps/core.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>${xmlEscape(book.title)}</dc:title>" +
                "<dc:creator>${xmlEscape(book.author)}</dc:creator></cp:coreProperties>"
        )
        used.mapNotNull { images[it] }.forEach { zip.add("word/media/${it.id}.${it.extension}", it.bytes) }
        if (cover != null && thumbExt != null) zip.add("docProps/thumbnail.$thumbExt", cover.bytes)
        return zip.finish()
    }

    private fun paragraph(block: Block): String {
        val props = when (block.type) {
            BlockType.HEADING -> "<w:pStyle w:val=\"Heading${block.level.coerceIn(1, 6)}\"/>"
            BlockType.QUOTE -> "<w:pStyle w:val=\"Quote\"/>"
            BlockType.CODE -> "<w:pStyle w:val=\"Code\"/>"
            BlockType.LIST_ITEM -> "<w:pStyle w:val=\"ListBullet\"/><w:numPr><w:ilvl w:val=\"${block.level.coerceIn(0, 8)}\"/><w:numId w:val=\"1\"/></w:numPr>"
            else -> ""
        }
        val runs = if (block.type == BlockType.CODE) {
            // Строки кода — переносы внутри абзаца
            block.text.lines().joinToString("<w:r><w:br/></w:r>") { run(it, bold = false, italic = false, code = true) }
        } else {
            block.runs().joinToString("") { run(it.text, it.bold, it.italic, it.code) }
        }
        return "<w:p>${if (props.isEmpty()) "" else "<w:pPr>$props</w:pPr>"}$runs</w:p>"
    }

    private fun run(text: String, bold: Boolean, italic: Boolean, code: Boolean): String {
        val props = (if (bold) "<w:b/>" else "") + (if (italic) "<w:i/>" else "") +
            (if (code) "<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/>" else "")
        return "<w:r>${if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"}<w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
    }

    private fun imageParagraph(image: ExportImage, rid: String, alt: String, id: Int): String {
        val size = image.size
        var cx = (size?.width ?: 600) * EMU_PER_PIXEL
        var cy = (size?.height ?: 400) * EMU_PER_PIXEL
        val scale = minOf(1.0, MAX_WIDTH_EMU.toDouble() / cx, MAX_HEIGHT_EMU.toDouble() / cy)
        cx = (cx * scale).toLong().coerceAtLeast(1)
        cy = (cy * scale).toLong().coerceAtLeast(1)
        val name = xmlEscape(image.id)
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$id\" name=\"$name\" descr=\"${xmlEscape(alt)}\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    private fun contentTypes(imageExtensions: Set<String>, thumbExt: String?): String {
        val defaults = (imageExtensions + listOfNotNull(thumbExt)).distinct().joinToString("") {
            val mime = when (it) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/bmp" }
            "<Default Extension=\"$it\" ContentType=\"$mime\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>$defaults" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>"
    }

    private fun rootRels(thumbExt: String?): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            (thumbExt?.let { "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail\" Target=\"docProps/thumbnail.$it\"/>" }.orEmpty()) +
            "</Relationships>"

    private fun documentRels(images: List<ExportImage>, relIds: Map<String, String>): String {
        val imageRels = images.joinToString("") {
            "<Relationship Id=\"${relIds.getValue(it.id)}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${it.id}.${it.extension}\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>" +
            "$imageRels</Relationships>"
    }

    private fun styles(): String {
        val headings = (1..6).joinToString("") { n ->
            val size = (36 - n * 3).coerceAtLeast(24)
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"$size\"/></w:rPr></w:style>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:styles xmlns:w=\"$W_NS\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Georgia\" w:hAnsi=\"Georgia\" w:cs=\"Georgia\"/><w:sz w:val=\"24\"/><w:lang w:val=\"ru-RU\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>$headings" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:ind w:left=\"567\"/></w:pPr><w:rPr><w:i/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/><w:sz w:val=\"20\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListBullet\"><w:name w:val=\"List Bullet\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"40\"/></w:pPr></w:style></w:styles>"
    }

    private fun numbering(): String {
        val levels = (0..8).joinToString("") { i ->
            "<w:lvl w:ilvl=\"$i\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/><w:lvlJc w:val=\"left\"/>" +
                "<w:pPr><w:ind w:left=\"${720 * (i + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:numbering xmlns:w=\"$W_NS\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>$levels</w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"
    }
}
