package com.localdoc.ai.book

/** Распознавание формата картинки по сигнатуре: расширение и тип из имени файла ненадёжны. */
object ImageFormat {

    /** MIME-тип поддерживаемой растровой картинки (PNG, JPEG, GIF, WebP, BMP) либо null. */
    fun mimeOf(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            bytes.size < 12 -> null
            at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == '8'.code -> "image/gif"
            at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "image/webp"
            at(0) == 'B'.code && at(1) == 'M'.code -> "image/bmp"
            else -> null
        }
    }

    fun extensionOf(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "bmp"
    }

    fun mimeOfExtension(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> null
    }
}

/**
 * Сборщик картинок книги при разборе. Ограничения защищают память: одна картинка не больше
 * [maxEach] байт, все вместе — не больше [maxTotal]; неподходящие молча пропускаются
 * (текст книги важнее картинок). Одинаковые картинки хранятся один раз.
 */
class ImageCollector(private val maxEach: Int = MAX_IMAGE_BYTES, private val maxTotal: Long = MAX_BOOK_IMAGE_BYTES) {

    private val data = LinkedHashMap<String, ByteArray>()
    private val byContent = HashMap<Int, MutableList<String>>()
    private var total = 0L

    /** Идентификатор картинки в книге (`im0`, `im1`…) либо null, если картинку нельзя принять. */
    fun add(bytes: ByteArray): String? {
        if (bytes.size > maxEach || ImageFormat.mimeOf(bytes) == null) return null
        val hash = bytes.contentHashCode()
        byContent[hash]?.firstOrNull { data[it]?.contentEquals(bytes) == true }?.let { return it }
        if (total + bytes.size > maxTotal) return null
        val id = "im${data.size}"
        data[id] = bytes
        byContent.getOrPut(hash) { ArrayList() }.add(id)
        total += bytes.size
        return id
    }

    val ids: List<String> get() = data.keys.toList()
    val images: Map<String, ByteArray> get() = data

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_BOOK_IMAGE_BYTES = 64L * 1024 * 1024
    }
}

/** Подставляет собранные картинки и обложку в готовую книгу. */
internal fun Book.withImages(collector: ImageCollector, cover: String? = this.cover): Book =
    copy(images = collector.ids, imageData = collector.images, cover = cover)

/** Превращает адрес картинки из разметки в ссылку на ресурс книги или в http(s)-адрес; null — пропустить. */
fun interface ImageResolver {
    fun resolve(src: String): String?
}

/**
 * Для HTML без собственного хранилища: `data:`-картинки сохраняются в книгу, абсолютные и относительные
 * (относительно [baseUrl]) http(s)-адреса остаются ссылками и грузятся лениво. Не больше [MAX_REFS] на документ.
 */
class HtmlImageResolver(private val baseUrl: String?, private val collector: ImageCollector) : ImageResolver {

    private var refs = 0

    override fun resolve(src: String): String? {
        if (refs >= MAX_REFS) return null
        val ref = when {
            src.startsWith("data:image/", ignoreCase = true) -> decodeDataUri(src)?.let(collector::add)
            else -> remoteUrl(src)
        }
        if (ref != null) refs++
        return ref
    }

    private fun remoteUrl(src: String): String? {
        val absolute = when {
            src.startsWith("http://", true) || src.startsWith("https://", true) -> src
            baseUrl != null && (baseUrl.startsWith("http://", true) || baseUrl.startsWith("https://", true)) ->
                WebNavigationDetector.resolveUrl(baseUrl, src)
            else -> null
        }
        return absolute?.takeIf { it.length <= MAX_URL }
    }

    companion object {
        const val MAX_REFS = 400
        private const val MAX_URL = 2000
        private const val MAX_DATA_URI = 12 * 1024 * 1024

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun decodeDataUri(uri: String): ByteArray? {
            val comma = uri.indexOf(',')
            if (comma < 0 || uri.length > MAX_DATA_URI || !uri.substring(0, comma).contains(";base64", ignoreCase = true)) return null
            return runCatching { kotlin.io.encoding.Base64.Mime.decode(uri.substring(comma + 1)) }.getOrNull()
        }
    }
}

/** Картинка из архива (EPUB, DOCX, ODT) по относительному пути; data:-URI и http(s) обрабатываются как в HTML. */
internal fun zipImage(zip: ZipArchive, baseDir: String, src: String, images: ImageCollector): String? = when {
    src.startsWith("data:image/", ignoreCase = true) -> HtmlImageResolver.decodeDataUri(src)?.let(images::add)
    src.startsWith("http://", true) || src.startsWith("https://", true) -> src.takeIf { it.length <= 2000 }
    else -> runCatching { zip.read(resolveZipPath(baseDir, src)) }.getOrNull()?.let(images::add)
}

/** Обложка веб-страницы: Open Graph / Twitter-карточка / `image_src`. */
object PageCover {
    fun find(doc: com.fleeksoft.ksoup.nodes.Element, pageUrl: String, images: ImageCollector? = null): String? {
        val candidates = listOf(
            doc.select("meta[property=og:image]").firstOrNull()?.attr("content"),
            doc.select("meta[property=og:image:url]").firstOrNull()?.attr("content"),
            doc.select("meta[name=twitter:image]").firstOrNull()?.attr("content"),
            doc.select("link[rel=image_src]").firstOrNull()?.attr("href")
        )
        val src = candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
        if (src.startsWith("data:image/", ignoreCase = true)) return images?.let { c -> HtmlImageResolver.decodeDataUri(src)?.let(c::add) }
        if (src.startsWith("data:")) return null
        val absolute = if (src.startsWith("http://", true) || src.startsWith("https://", true)) src else WebNavigationDetector.resolveUrl(pageUrl, src)
        return absolute?.takeIf { it.length <= 2000 }
    }
}

/**
 * Поиск PNG/JPEG внутри бинарного потока (OLE2-документы Word/WPS хранят картинки в потоке `Data`
 * без каталога). Границы: PNG — до чанка IEND, JPEG — до маркера FFD9. Место картинки в тексте
 * неизвестно, поэтому вызывающий добавляет их в конец книги.
 */
internal object EmbeddedImageScanner {

    fun scan(data: ByteArray, collector: ImageCollector, limit: Int = 60): List<String> {
        val ids = ArrayList<String>()
        var i = 0
        while (i + 8 < data.size && ids.size < limit) {
            val end = when {
                isPngAt(data, i) -> pngEnd(data, i)
                data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte() -> jpegEnd(data, i)
                else -> -1
            }
            if (end > i) {
                collector.add(data.copyOfRange(i, end))?.let { if (it !in ids) ids.add(it) }
                i = end
            } else i++
        }
        return ids
    }

    private fun isPngAt(d: ByteArray, i: Int) =
        d[i] == 0x89.toByte() && d[i + 1] == 'P'.code.toByte() && d[i + 2] == 'N'.code.toByte() && d[i + 3] == 'G'.code.toByte()

    private fun pngEnd(d: ByteArray, from: Int): Int {
        var p = from + 8
        while (p + 12 <= d.size) {
            val len = ((d[p].toInt() and 0xFF) shl 24) or ((d[p + 1].toInt() and 0xFF) shl 16) or
                ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
            if (len < 0 || p + 12 + len > d.size) return -1
            val isEnd = d[p + 4] == 'I'.code.toByte() && d[p + 5] == 'E'.code.toByte() && d[p + 6] == 'N'.code.toByte() && d[p + 7] == 'D'.code.toByte()
            p += 12 + len
            if (isEnd) return p
        }
        return -1
    }

    private fun jpegEnd(d: ByteArray, from: Int): Int {
        var p = from + 2
        while (p + 1 < d.size) {
            if (d[p] == 0xFF.toByte() && d[p + 1] == 0xD9.toByte()) return p + 2
            p++
        }
        return -1
    }
}
