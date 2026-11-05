package com.localdoc.ai.novel

import com.localdoc.ai.book.LibraryRepository
import com.localdoc.ai.domain.NovelDownloader
import kotlinx.coroutines.CancellationException

/** Чем закончилась фоновая докачка одной новеллы. */
sealed interface RefreshOutcome {
    /** Книга обновлена: скачано [chapters] новых глав. */
    class Updated(val chapters: Int) : RefreshOutcome
    /** Новых глав не оказалось (проверка нашла, а при скачивании список не вырос) или книга уже не в библиотеке. */
    data object NothingToDo : RefreshOutcome
    /** Сайт недоступен или попросил подождать — книга остаётся с новыми главами в списке ожидания. */
    class Failed(val reason: String) : RefreshOutcome
}

/**
 * Фоновая докачка новелл: после проверки, нашедшей новые главы, книга пересобирается тем же
 * [NovelDownloader] — уже скачанные главы берутся с диска, качаются только новые, поэтому
 * нагрузка на сайт минимальна. Готовая книга заменяет прежнюю в библиотеке: запись находится
 * по адресу источника, прогресс чтения сохраняется.
 */
class NovelAutoRefresh(
    private val downloader: NovelDownloader,
    private val library: LibraryRepository,
    private val tracker: NovelTracker,
    private val pause: suspend () -> Unit = {}
) {

    /**
     * Докачивает новые главы одной новеллы; [urls] — главы, которые проверка нашла и ждут скачивания.
     * Возвращает результат: пользователь уведомляется только о реально скачанных главах.
     */
    suspend fun refresh(novel: TrackedNovel, urls: List<String>): RefreshOutcome {
        // Книга в библиотеке: собранная из глав хранит адрес источника, равный адресу оглавления
        val host = hostOf(novel.tocUrl)
        val entry = library.entries.value.firstOrNull { it.source == novel.tocUrl }
            ?: library.entries.value.firstOrNull { hostOf(it.source) == host }
            ?: return RefreshOutcome.NothingToDo
        return try {
            val result = downloader.run(
                startUrl = entry.source,
                tocUrl = novel.tocUrl,
                knownToc = emptyList(),
                progress = { _ -> },
                allowFile = false
            )
            library.add(result.book)
            pause()
            val downloaded = result.downloadedUrls.map(ChapterDiff::normalizeUrl)
            val added = urls.count { ChapterDiff.normalizeUrl(it) in downloaded }
            // Часть могла остаться недоступной: она ждёт следующей проверки
            tracker.markDownloaded(novel.id, downloaded)
            if (added > 0) RefreshOutcome.Updated(added) else RefreshOutcome.NothingToDo
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RefreshOutcome.Failed(e.message ?: "Не удалось докачать главы")
        }
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').lowercase().removePrefix("www.")
}
