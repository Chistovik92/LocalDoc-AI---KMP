package com.localdoc.ai.domain

import com.localdoc.ai.book.Book
import com.localdoc.ai.book.LibraryRepository
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.book.WebNavigation
import com.localdoc.ai.parser.WebArticleParser
import com.localdoc.ai.tts.ReadAloudController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Открытая веб-страница в режиме чтения. */
data class WebDocument(val book: Book, val entryId: String, val links: List<PageLink>, val startBlock: Int)

/**
 * Состояние вкладки «Веб»: загрузка страниц, переходы по главам веб-новелл и оглавление.
 * Если на странице нет явных маркеров «Далее / Оглавление», ссылку выбирает локальная модель.
 */
class WebSession(
    private val parser: WebArticleParser,
    private val library: LibraryRepository,
    private val pipeline: () -> SmartPipeline,
    private val isModelLoaded: () -> Boolean,
    private val readAloud: () -> ReadAloudController,
    private val scope: CoroutineScope
) {
    private val _document = MutableStateFlow<WebDocument?>(null)
    val document: StateFlow<WebDocument?> = _document.asStateFlow()

    private val _loading = MutableStateFlow<String?>(null)
    val loading: StateFlow<String?> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toc = MutableStateFlow<List<PageLink>>(emptyList())
    val toc: StateFlow<List<PageLink>> = _toc.asStateFlow()

    private val _tocLoading = MutableStateFlow(false)
    val tocLoading: StateFlow<Boolean> = _tocLoading.asStateFlow()

    private var job: Job? = null

    fun docKey(doc: WebDocument) = "web:${doc.entryId}"

    fun open(url: String) {
        job?.cancel()
        val previousKey = _document.value?.let(::docKey)
        job = scope.launch {
            _loading.value = url
            _error.value = null
            try {
                val page = parser.load(url)
                require(page.book.blocks.isNotEmpty()) { "На странице не найден текст для чтения" }
                var book = page.book
                // Нет маркера «следующая глава» — спрашиваем локальную модель
                if (book.web?.next == null && isModelLoaded() && looksLikeChapter(book)) {
                    val next = runCatching { pipeline().pickNavigationLink(page.links, "следующую главу") }.getOrNull()
                    if (next != null && next != book.source) book = book.copy(web = (book.web ?: WebNavigation()).copy(next = next))
                }
                val entry = library.add(book)
                val doc = WebDocument(book, entry.id, page.links, entry.progressBlock)
                _document.value = doc
                _toc.value = emptyList()
                // Пользователь сам перешёл к следующей главе во время озвучки — продолжаем читать её
                val player = readAloud()
                if (previousKey != null && player.documentKey == previousKey) {
                    player.start(docKey(doc), book.blocks, book.chapters, 0, book.title)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _error.value = "Не удалось открыть страницу: ${t.message ?: t::class.simpleName}"
            } finally {
                _loading.value = null
            }
        }
    }

    /** Открытие сохранённой страницы из библиотеки без сети. */
    fun openSaved(entryId: String) {
        job?.cancel()
        job = scope.launch {
            val book = library.load(entryId)
            if (book == null) {
                _error.value = "Страница не найдена в кэше"
                return@launch
            }
            library.touch(entryId)
            _document.value = WebDocument(book, entryId, emptyList(), library.entry(entryId)?.progressBlock ?: 0)
            _toc.value = emptyList()
        }
    }

    fun next() = _document.value?.book?.web?.next?.let(::open)
    fun previous() = _document.value?.book?.web?.prev?.let(::open)

    fun close() {
        job?.cancel()
        _document.value = null
        _toc.value = emptyList()
    }

    fun saveProgress(block: Int) {
        _document.value?.let { library.saveProgress(it.entryId, block) }
    }

    /** Оглавление новеллы: страница по ссылке «Оглавление», иначе — подсказка модели или ссылки текущей страницы. */
    fun loadTableOfContents() {
        val doc = _document.value ?: return
        scope.launch {
            _tocLoading.value = true
            try {
                var tocUrl = doc.book.web?.toc
                if (tocUrl == null && isModelLoaded() && doc.links.isNotEmpty()) {
                    tocUrl = runCatching { pipeline().pickNavigationLink(doc.links, "оглавление (список всех глав)") }.getOrNull()
                }
                _toc.value = when {
                    tocUrl != null -> parser.loadTableOfContents(tocUrl)
                    doc.links.isNotEmpty() -> chapterLikeLinks(doc.links, doc.book.source)
                    else -> emptyList()
                }
                if (_toc.value.isEmpty()) _error.value = "Оглавление не найдено на этой странице"
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _error.value = "Не удалось загрузить оглавление: ${t.message}"
            } finally {
                _tocLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun looksLikeChapter(book: Book): Boolean =
        CHAPTER_HINT.containsMatchIn(book.title) || CHAPTER_HINT.containsMatchIn(book.source)

    private fun chapterLikeLinks(links: List<PageLink>, pageUrl: String): List<PageLink> {
        val host = pageUrl.substringAfter("://").substringBefore('/')
        return links.filter { it.url.contains(host) && CHAPTER_HINT.containsMatchIn(it.text) }.distinctBy { it.url }
    }

    private companion object {
        val CHAPTER_HINT = Regex("глава|chapter|ch\\.?\\s*\\d|том|часть|/\\d+/?$", RegexOption.IGNORE_CASE)
    }
}
