package com.localdoc.ai.book

/**
 * CBZ (комиксы): ZIP, в котором лежат только страницы-картинки. Страницы идут в «натуральном» порядке имён
 * (`2.jpg` раньше `10.jpg`); каждая страница — отдельный блок-картинка, первая — обложка. Текста нет.
 */
object CbzParser {

    /** Картинок в архиве больше нескольких и ни одного документа книги — это комикс, а не EPUB с иллюстрациями. */
    fun isCbz(zip: ZipArchive): Boolean {
        val pages = pageNames(zip)
        if (pages.isEmpty()) return false
        val others = zip.names.count { !it.endsWith("/") && it !in pages && !isMetadata(it) }
        return others == 0
    }

    fun parse(zip: ZipArchive, fallbackTitle: String): Book {
        val names = pageNames(zip)
        require(names.isNotEmpty()) { "В архиве нет страниц-картинок" }
        val images = ImageCollector(maxEach = MAX_PAGE_BYTES, maxTotal = MAX_BOOK_BYTES)
        val blocks = ArrayList<Block>()
        var cover: String? = null
        for ((index, name) in names.withIndex()) {
            val id = zip.read(name)?.let(images::add) ?: continue
            if (cover == null) cover = id
            blocks.add(Block(BlockType.IMAGE, "Страница ${index + 1}", image = id))
        }
        require(blocks.isNotEmpty()) { "Не удалось прочитать страницы комикса" }
        val chapters = listOf(Chapter("Страница 1", 0))
        return Book(title = fallbackTitle, format = BookFormat.CBZ, blocks = blocks, chapters = chapters).withImages(images, cover)
    }

    /** Имена страниц в порядке чтения; служебные папки и скрытые файлы пропускаются. */
    internal fun pageNames(zip: ZipArchive): List<String> =
        zip.names.filter { name ->
            !name.endsWith("/") && !isMetadata(name) && ImageFormat.mimeOfExtension(name) != null
        }.sortedWith(NaturalOrder)

    private fun isMetadata(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return name.startsWith("__MACOSX/") || base.startsWith(".") || base.equals("Thumbs.db", true) ||
            base.equals("ComicInfo.xml", true) || base.equals("metadata.opf", true)
    }

    /** Натуральный порядок: числа внутри имён сравниваются как числа, регистр не важен. */
    internal object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    var ei = i
                    while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j
                    while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0')
                    val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val cmp = na.compareTo(nb)
                    if (cmp != 0) return cmp
                    i = ei
                    j = ej
                } else {
                    val ca = a[i].lowercaseChar()
                    val cb = b[j].lowercaseChar()
                    if (ca != cb) return ca.compareTo(cb)
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }

    private const val MAX_PAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_BOOK_BYTES = 256L * 1024 * 1024
}
