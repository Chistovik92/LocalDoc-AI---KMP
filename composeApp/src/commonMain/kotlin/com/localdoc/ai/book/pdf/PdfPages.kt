package com.localdoc.ai.book.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Рисует страницы PDF картинками. Платформенные реализации — [com.localdoc.ai.platform.PdfPageRenderer]. */
interface PageRenderer {
    val pageCount: Int

    /** Страница [index] (с нуля) как JPEG/PNG шириной не больше [widthPx]; null — страницу нарисовать не удалось. */
    fun render(index: Int, widthPx: Int): ByteArray?

    fun close()
}

/** Размер картинки страницы: ширина экрана, но не больше [MAX_PIXELS] — одна страница не должна съедать память. */
object PdfRenderSize {
    const val MAX_PIXELS = 6_000_000L
    const val MAX_SIDE = 4096

    /** Размер в пикселях для страницы [pageWidth] × [pageHeight] пунктов при желаемой ширине [targetWidth]. */
    fun fit(pageWidth: Double, pageHeight: Double, targetWidth: Int): Pair<Int, Int> {
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return 1 to 1
        var scale = targetWidth.coerceAtLeast(1) / pageWidth
        if (pageWidth * scale > MAX_SIDE) scale = MAX_SIDE / pageWidth
        if (pageHeight * scale > MAX_SIDE) scale = MAX_SIDE / pageHeight
        val pixels = pageWidth * scale * pageHeight * scale
        if (pixels > MAX_PIXELS) scale *= kotlin.math.sqrt(MAX_PIXELS / pixels)
        return (pageWidth * scale).toInt().coerceAtLeast(1) to (pageHeight * scale).toInt().coerceAtLeast(1)
    }
}

/**
 * Кэш готовых страниц с ограничением по байтам: самые давние по обращению вытесняются.
 * Страница, не помещающаяся в лимит, в кэш не кладётся.
 */
class PageCache(private val maxBytes: Long) {
    private val map = LinkedHashMap<String, ByteArray>()
    var bytes = 0L
        private set

    val size: Int get() = map.size

    fun get(key: String): ByteArray? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    fun put(key: String, value: ByteArray) {
        map.remove(key)?.let { bytes -= it.size }
        if (value.size > maxBytes) return
        map[key] = value
        bytes += value.size
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val oldest = it.next()
            if (oldest.key == key) continue
            bytes -= oldest.value.size
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        bytes = 0
    }
}

/**
 * Источник страниц для читалки: рисует по одной странице за раз (рендерер не потокобезопасен и держит
 * страницу в памяти), хранит результат в [PageCache].
 */
class PdfPageSource(
    private val renderer: PageRenderer,
    private val dispatcher: CoroutineDispatcher,
    maxCacheBytes: Long = DEFAULT_CACHE_BYTES
) {
    private val lock = Mutex()
    private val cache = PageCache(maxCacheBytes)

    val pageCount: Int get() = renderer.pageCount

    suspend fun page(index: Int, widthPx: Int): ByteArray? {
        if (index !in 0 until pageCount) return null
        val key = "$index@$widthPx"
        return lock.withLock {
            cache.get(key) ?: withContext(dispatcher) { runCatching { renderer.render(index, widthPx) }.getOrNull() }
                ?.also { cache.put(key, it) }
        }
    }

    val cachedBytes: Long get() = cache.bytes

    fun close() {
        cache.clear()
        renderer.close()
    }

    companion object {
        const val DEFAULT_CACHE_BYTES = 24L * 1024 * 1024
    }
}

/** Связь книги-PDF с копией файла в хранилище приложения. */
object PdfPages {
    /** Больше этого размера копию не держим: книга остаётся только в режиме «текст». */
    const val MAX_COPY_BYTES = 600L * 1024 * 1024
    private const val COVER_WIDTH = 480

    /** Имя копии: одно и то же для одного источника, поэтому повторный импорт её заменяет, а не плодит. */
    fun fileNameFor(source: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV-1a, 64 бита
        for (b in source.encodeToByteArray()) hash = (hash xor (b.toLong() and 0xFF)) * 0x100000001b3L
        return "pdf/" + hash.toULong().toString(16) + ".pdf"
    }

    /** Обложка PDF — первая страница; если нарисовать не удалось, книга остаётся без обложки. */
    fun withCover(book: com.localdoc.ai.book.Book, renderer: PageRenderer): com.localdoc.ai.book.Book {
        val bytes = runCatching { renderer.render(0, COVER_WIDTH) }.getOrNull() ?: return book
        val images = com.localdoc.ai.book.ImageCollector()
        val id = images.add(bytes) ?: return book
        return book.copy(images = book.images + id, imageData = book.imageData + (id to bytes), cover = id)
    }
}
