package com.localdoc.ai.domain

import com.localdoc.ai.i18n.L10n
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.FormatDetector
import com.localdoc.ai.book.LibraryRepository
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.book.embedRemoteImages
import com.localdoc.ai.book.export.BookExporter
import com.localdoc.ai.book.export.ExportFormat
import com.localdoc.ai.images.ImageLoader
import com.localdoc.ai.parser.WebArticleParser
import com.localdoc.ai.parser.WebPage
import com.localdoc.ai.platform.FileSaver
import com.localdoc.ai.platform.SaveResult
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.download_added_to_library
import com.localdoc.ai.resources.download_book_missing
import com.localdoc.ai.resources.download_cancelled_summary
import com.localdoc.ai.resources.download_failed_generic
import com.localdoc.ai.resources.download_file_saved
import com.localdoc.ai.resources.download_images
import com.localdoc.ai.resources.download_missing_chapters
import com.localdoc.ai.resources.download_save_cancelled
import com.localdoc.ai.resources.download_save_cancelled_added
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Задача «Скачать / Сохранить как…»: что делается, на каком этапе и чем закончилось. */
data class DownloadJob(
    val title: String,
    val format: ExportFormat,
    val progress: DownloadProgress,
    /** Итоговое сообщение для пользователя (где сохранено, сколько глав не удалось скачать, причина ошибки). */
    val summary: String? = null
) {
    val finished: Boolean
        get() = progress.phase == DownloadPhase.DONE || progress.phase == DownloadPhase.FAILED || progress.phase == DownloadPhase.CANCELLED
}

/**
 * Скачивание книг и конвертация («Сохранить как…»): одна задача за раз, с прогрессом и отменой.
 * Пользователю не нужно ничего листать: оглавление, главы и картинки приложение собирает само,
 * затем книга пишется в выбранный формат и сохраняется в выбранное место (через системное окно).
 */
class BookDownloadManager(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val downloader: NovelDownloader,
    private val parser: WebArticleParser,
    private val images: ImageLoader,
    private val saver: FileSaver,
    private val resolveToc: suspend (tocUrl: String?, page: WebPage) -> List<PageLink>,
    /** Новелла скачана целиком по оглавлению: автообновление берёт её под наблюдение. */
    private val onNovelDownloaded: (title: String, result: NovelResult) -> Unit = { _, _ -> }
) {
    private val _job = MutableStateFlow<DownloadJob?>(null)
    val job: StateFlow<DownloadJob?> = _job.asStateFlow()

    private var running: Job? = null

    val busy: Boolean get() = _job.value?.finished == false

    /** Скачать произведение, к странице которого относится запись, целиком и сохранить в [format]. */
    fun downloadWhole(entry: BookEntry, format: ExportFormat, addToLibrary: Boolean) {
        if (busy) return
        start(NovelTitles.guess(entry.title, entry.source), format) {
            update(DownloadProgress(DownloadPhase.LOOKING))
            val savedToc = library.load(entry.id)?.web?.toc
            val page = parser.load(entry.source)
            val tocUrl = savedToc ?: page.book.web?.toc
            val toc = runCatching { resolveToc(tocUrl, page) }.getOrDefault(emptyList())
            val result = downloader.run(entry.source, tocUrl, toc) { update(it) }
            onNovelDownloaded(result.book.title, result)
            val note = if (result.missing > 0) L10n.get(Res.string.download_missing_chapters, result.missing.toString()) else ""
            finish(result.book, format, addToLibrary, result.original?.let { it.name to it.bytes }, note)
        }
    }

    /** «Сохранить как…» для книги из библиотеки: импорт → Book → выбранный формат. */
    fun convert(entry: BookEntry, format: ExportFormat) {
        if (busy) return
        start(entry.title, format) {
            update(DownloadProgress(DownloadPhase.BUILDING))
            val book = library.loadWithImages(entry.id) ?: throw NovelDownloadException(L10n.get(Res.string.download_book_missing))
            finish(book, format, addToLibrary = false, original = null, note = "")
        }
    }

    fun cancel() {
        running?.cancel()
    }

    fun dismiss() {
        if (!busy) _job.value = null
    }

    private fun start(title: String, format: ExportFormat, block: suspend () -> Unit) {
        _job.value = DownloadJob(title, format, DownloadProgress(DownloadPhase.LOOKING))
        running = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                _job.value = _job.value?.copy(
                    progress = DownloadProgress(DownloadPhase.CANCELLED),
                    summary = L10n.get(Res.string.download_cancelled_summary)
                )
                throw e
            } catch (e: Exception) {
                _job.value = _job.value?.copy(progress = DownloadProgress(DownloadPhase.FAILED), summary = e.message ?: L10n.get(Res.string.download_failed_generic))
            }
        }
    }

    private fun update(progress: DownloadProgress) {
        _job.value = _job.value?.copy(progress = progress)
    }

    private suspend fun finish(book: Book, format: ExportFormat, addToLibrary: Boolean, original: Pair<String, ByteArray>?, note: String) {
        // Файл сайта в нужном формате сохраняется как есть — без потерь от повторной сборки
        val originalFormat = original?.let { runCatching { FormatDetector.resolve(it.first, it.second) }.getOrNull() }
        val sameAsOriginal = original != null && originalFormat == format.importFormat && format != ExportFormat.FB2_ZIP &&
            original.first.lowercase().endsWith("." + format.extension)

        update(DownloadProgress(DownloadPhase.EXPORTING))
        val prepared = if (format == ExportFormat.TXT || sameAsOriginal) {
            book
        } else {
            val referer = book.source.takeIf { it.startsWith("http") }
            val imagesLabel = L10n.get(Res.string.download_images)
            book.embedRemoteImages(onProgress = { done, total -> update(DownloadProgress(DownloadPhase.EXPORTING, done, total, imagesLabel)) }) { url ->
                images.bytes(null, url, referer)
            }
        }
        val bytes = if (sameAsOriginal && original != null) original.second else withContext(Dispatchers.Default) { BookExporter.export(prepared, format) }

        if (addToLibrary) library.add(prepared)

        update(DownloadProgress(DownloadPhase.SAVING))
        val name = BookExporter.suggestedName(book, format)
        val saved = CompletableDeferred<SaveResult>()
        saver.save(name, format.mime, bytes) { saved.complete(it) }
        val libraryNote = if (addToLibrary) " " + L10n.get(Res.string.download_added_to_library) else ""
        when (val result = saved.await()) {
            is SaveResult.Saved -> _job.value = _job.value?.copy(
                progress = DownloadProgress(DownloadPhase.DONE),
                summary = listOf(note, L10n.get(Res.string.download_file_saved, name) + libraryNote)
                    .filter { it.isNotBlank() }.joinToString(" ")
            )
            SaveResult.Cancelled -> _job.value = _job.value?.copy(
                progress = DownloadProgress(DownloadPhase.DONE),
                summary = listOfNotNull(
                    note.ifBlank { null },
                    if (addToLibrary) L10n.get(Res.string.download_save_cancelled_added) else L10n.get(Res.string.download_save_cancelled)
                ).joinToString(" ")
            )
            is SaveResult.Failed -> throw NovelDownloadException(result.message)
        }
    }
}
