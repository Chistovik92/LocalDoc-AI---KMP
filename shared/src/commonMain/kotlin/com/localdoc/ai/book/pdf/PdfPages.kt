package com.localdoc.ai.book.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Рисует страницы PDF картинками. Платформенные реализации — [com.localdoc.ai.platform.PdfPageRenderer]. */
interface PageRenderer {
    val pageCount: Int

    /**
     * Страница [index] (с нуля) как JPEG/PNG шириной не больше [widthPx]; null — страницу нарисовать не удалось.
     * [maxPixels] — бюджет пикселей одной страницы (по умолчанию [PdfRenderSize.MAX_PIXELS]); увеличенный
     * бюджет передаётся для перерисовки при зуме: одна страница в кадре, поэтому острота важнее экономии.
     */
    fun render(index: Int, widthPx: Int, maxPixels: Long = PdfRenderSize.MAX_PIXELS): ByteArray?

    fun close()
}

/** Размер картинки страницы: ширина экрана, но не больше [MAX_PIXELS] — одна страница не должна съедать память.
 *  3 Мпикс — это 6–12 МБ на страницу в памяти декодирования (RGB_565×2 / ARGB_8888×4 байта);
 *  нормально отрисованный разворот А4 в экран влезает впятеро. */
object PdfRenderSize {
    const val MAX_PIXELS = 3_000_000L
    const val MAX_SIDE = 4096

    /** Страница нулевого или отрицательного размера (битый PDF): рисуем пропорцию А4, а не квадрат 1×1. */
    private const val FALLBACK_WIDTH = 595.0
    private const val FALLBACK_HEIGHT = 842.0

    /** Размер в пикселях для страницы [pageWidth] × [pageHeight] пунктов при желаемой ширине [targetWidth]
     *  и бюджете [maxPixels] пикселей на страницу. */
    fun fit(pageWidth: Double, pageHeight: Double, targetWidth: Int, maxPixels: Long = MAX_PIXELS): Pair<Int, Int> {
        val width = if (pageWidth > 0.0) pageWidth else FALLBACK_WIDTH
        val height = if (pageHeight > 0.0) pageHeight else FALLBACK_HEIGHT
        var scale = targetWidth.coerceAtLeast(1) / width
        if (width * scale > MAX_SIDE) scale = MAX_SIDE / width
        if (height * scale > MAX_SIDE) scale = MAX_SIDE / height
        val pixels = width * scale * height * scale
        if (pixels > maxPixels) scale *= kotlin.math.sqrt(maxPixels / pixels)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
}

/** Порог увеличения, до которого страница рисуется базовой шириной: перерисовка на каждый пиксель зума не нужна. */
const val ZOOM_SHARP_THRESHOLD = 1.25f

/**
 * Ширина рендера страницы при увеличении [scale]: базовая [base] до [ZOOM_SHARP_THRESHOLD], дальше — кратно
 * зуму (ступени округляются вверх: 1,3→2×base, 2,9→3×base), но не шире [maxWidth]. Ступени, а не пропорция,
 * чтобы плавный щипок не запускал перерисовку на каждое движение пальцев.
 */
fun zoomRenderWidth(scale: Float, base: Int, maxWidth: Int): Int {
    if (scale <= ZOOM_SHARP_THRESHOLD) return base
    val factor = kotlin.math.ceil(scale).toInt().coerceAtLeast(2)
    // Потолок не ниже базы: перерисовка при зуме не должна уменьшать страницу
    val cap = maxWidth.toLong().coerceAtLeast(base.toLong())
    return (base.toLong() * factor).coerceAtMost(cap).toInt()
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

/** Готовая страница или причина ее сбоя, вместо молчаливого null. */
sealed interface PdfPageResult {
    data class Ready(val bytes: ByteArray) : PdfPageResult
    data class Failed(val reason: String) : PdfPageResult
}

/**
 * Источник страниц для читалки: рисует по одной странице за раз (рендерер не потокобезопасен и держит
 * страницу в памяти), хранит результат в [PageCache]. Отмена не превращается в «страницу не открыли»:
 * она выбрасывается как есть, а позади экрана запрос можно перезапустить.
 */
class PdfPageSource(
    private val renderer: PageRenderer,
    private val dispatcher: CoroutineDispatcher,
    maxCacheBytes: Long = DEFAULT_CACHE_BYTES
) {
    private val lock = Mutex()
    private val cache = PageCache(maxCacheBytes)

    val pageCount: Int get() = renderer.pageCount

    suspend fun page(index: Int, widthPx: Int, maxPixels: Long = PdfRenderSize.MAX_PIXELS): PdfPageResult {
        if (index !in 0 until pageCount) return PdfPageResult.Failed(noPageReason(index))
        val key = "$index@$widthPx@$maxPixels"
        return lock.withLock {
            cache.get(key)?.let { return PdfPageResult.Ready(it) }
            val bytes = try {
                withContext(dispatcher) { renderer.render(index, widthPx, maxPixels) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                com.localdoc.ai.platform.DebugLog.e("PDF page $index render failed", t)
                return PdfPageResult.Failed(t.message?.takeIf { it.isNotBlank() }
                    ?: "Страницу не удалось нарисовать (${t::class.simpleName})")
            }
            bytes?.also { cache.put(key, it) }?.let { PdfPageResult.Ready(it) }
                ?: PdfPageResult.Failed("Рендер вернул пустую страницу. Попробуйте другой PDF-файл или режим «текст».")
        }
    }

    private fun noPageReason(index: Int): String = "Страница ${index + 1} вне документа (всего $pageCount)"

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

/** Блок, с которого книга-PDF считает страницу [page]: оглавление «Страница N», а без него — пропорционально. */
fun com.localdoc.ai.book.Book.blockOfPdfPage(page: Int, pageCount: Int): Int {
    if (blocks.isEmpty()) return 0
    val byChapter = chapters.takeIf { it.size == pageCount }?.getOrNull(page)?.startBlock
    return (byChapter ?: (page.toLong() * blocks.size / pageCount.coerceAtLeast(1)).toInt()).coerceIn(0, blocks.size - 1)
}

/** Страница PDF, на которую попадает блок [block] (обратное к [blockOfPdfPage]). */
fun com.localdoc.ai.book.Book.pdfPageOfBlock(block: Int, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    val page = if (chapters.size == pageCount) chapterIndexAt(block).coerceAtLeast(0)
    else (block.toLong() * pageCount / blocks.size.coerceAtLeast(1)).toInt()
    return page.coerceIn(0, pageCount - 1)
}
