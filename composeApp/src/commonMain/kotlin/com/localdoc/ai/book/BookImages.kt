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
