package com.localdoc.ai.book.export

import com.localdoc.ai.book.Book

/**
 * EPUB 3 с совместимым оглавлением EPUB 2 (toc.ncx): по файлу на главу, картинки в `images/`,
 * обложка помечена `cover-image` и `<meta name="cover">`. Файл `mimetype` идёт первым и без сжатия.
 */
internal object EpubWriter {

    private const val XHTML_NS = "http://www.w3.org/1999/xhtml"

    private class Part(val file: String, val title: String?, val blockRange: IntRange)

    fun write(book: Book): ByteArray {
        val images = book.exportImages()
        val usedIds = (book.blocks.mapNotNull { it.image } + listOfNotNull(book.cover)).filter { it in images }.toSet()
        val used = images.values.filter { it.id in usedIds }
        fun href(image: ExportImage) = "images/${image.id}.${image.extension}"

        val parts = splitIntoParts(book)
        val zip = ZipWriter()
        zip.add("mimetype", "application/epub+zip")
        zip.add(
            "META-INF/container.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        )

        // Главы
        for (part in parts) {
            val body = HtmlRender.blocks(book.blocks.slice(part.blockRange)) { ref ->
                if (ref.isRemoteImage()) ref else images[ref]?.let(::href)
            }
            zip.add("OEBPS/${part.file}", xhtml(part.title ?: book.title, body))
        }
        used.forEach { zip.add("OEBPS/${href(it)}", it.bytes) }

        val tocParts = parts.filter { it.title != null }
        zip.add("OEBPS/nav.xhtml", navDocument(book, tocParts))
        zip.add("OEBPS/toc.ncx", ncx(book, tocParts.ifEmpty { parts.take(1) }))
        zip.add("OEBPS/content.opf", opf(book, parts, used, ::href))
        return zip.finish()
    }

    /** Файл на главу; блоки до первой главы — отдельный файл без записи в оглавлении. */
    private fun splitIntoParts(book: Book): List<Part> {
        val starts = book.chapters.map { it.startBlock }.filter { it in book.blocks.indices }.distinct().sorted()
        if (starts.isEmpty()) return listOf(Part("text.xhtml", null, book.blocks.indices))
        val parts = ArrayList<Part>()
        if (starts.first() > 0) parts.add(Part("front.xhtml", null, 0 until starts.first()))
        starts.forEachIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] - 1 else book.blocks.lastIndex
            val title = book.chapters.first { it.startBlock == start }.title
            parts.add(Part("ch" + (i + 1).toString().padStart(4, '0') + ".xhtml", title, start..end))
        }
        return parts
    }

    private fun xhtml(title: String, body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"$XHTML_NS\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n" +
            "<head><meta charset=\"utf-8\"/><title>${xmlEscape(title)}</title></head>\n<body>\n$body</body>\n</html>\n"

    private fun navDocument(book: Book, parts: List<Part>): String {
        val items = parts.joinToString("\n") { "<li><a href=\"${it.file}\">${xmlEscape(it.title.orEmpty())}</a></li>" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"$XHTML_NS\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n" +
            "<head><meta charset=\"utf-8\"/><title>${xmlEscape(book.title)}</title></head>\n" +
            "<body><nav epub:type=\"toc\" id=\"toc\"><h1>Оглавление</h1><ol>\n$items\n</ol></nav></body>\n</html>\n"
    }

    private fun ncx(book: Book, parts: List<Part>): String {
        val points = parts.mapIndexed { i, p ->
            "<navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${xmlEscape(p.title ?: book.title)}</text></navLabel>" +
                "<content src=\"${p.file}\"/></navPoint>"
        }.joinToString("\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n" +
            "<head><meta name=\"dtb:uid\" content=\"${book.stableId()}\"/></head>\n<docTitle><text>${xmlEscape(book.title)}</text></docTitle>\n" +
            "<navMap>\n$points\n</navMap>\n</ncx>\n"
    }

    private fun opf(book: Book, parts: List<Part>, images: List<ExportImage>, href: (ExportImage) -> String): String {
        val cover = book.cover?.let { c -> images.firstOrNull { it.id == c } }
        val manifest = StringBuilder()
        manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        manifest.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        parts.forEachIndexed { i, p -> manifest.append("<item id=\"p${i + 1}\" href=\"${p.file}\" media-type=\"application/xhtml+xml\"/>\n") }
        images.forEach {
            val props = if (it === cover) " properties=\"cover-image\"" else ""
            manifest.append("<item id=\"img-${it.id}\" href=\"${href(it)}\" media-type=\"${it.mime}\"$props/>\n")
        }
        val spine = parts.indices.joinToString("\n") { "<itemref idref=\"p${it + 1}\"/>" }
        val author = if (book.author.isBlank()) "" else "<dc:creator>${xmlEscape(book.author)}</dc:creator>\n"
        val coverMeta = cover?.let { "<meta name=\"cover\" content=\"img-${it.id}\"/>\n" }.orEmpty()
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">\n" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n<dc:identifier id=\"bookid\">${book.stableId()}</dc:identifier>\n" +
            "<dc:title>${xmlEscape(book.title)}</dc:title>\n$author<dc:language>${book.guessLanguage()}</dc:language>\n$coverMeta" +
            "<meta property=\"dcterms:modified\">2000-01-01T00:00:00Z</meta>\n</metadata>\n<manifest>\n$manifest</manifest>\n" +
            "<spine toc=\"ncx\">\n$spine\n</spine>\n</package>\n"
    }
}
