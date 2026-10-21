package com.localdoc.ai.catalog

import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.FormatDetector
import com.localdoc.ai.domain.NovelFiles
import com.localdoc.ai.net.nowMillis
import com.localdoc.ai.platform.ScannedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Файл книги, найденный на устройстве или подключённом ресурсе. */
@Serializable
data class CatalogItem(
    val uri: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val folder: String,
    /** Откуда найден: `device` либо идентификатор выбранной папки / ресурса. */
    val root: String,
    val format: BookFormat,
    val title: String,
    val author: String = "",
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val language: String = "",
    val series: String = "",
    /** true — метаданные прочитаны из файла; false — пока только из имени. */
    val enriched: Boolean = false,
    val foundAt: Long = 0,
    /** Идентификатор записи библиотеки, если книгу уже добавили. */
    val libraryId: String? = null
)

/** Место, которое сканировали: всё устройство, папка, SD-карта, облако… */
@Serializable
data class CatalogRoot(val id: String, val title: String, val lastScan: Long = 0, val files: Int = 0)

/** Подборка пользователя: набор файлов под своим названием. */
@Serializable
data class UserShelf(val id: String, val name: String, val uris: List<String>)

@Serializable
private class CatalogData(val items: List<CatalogItem> = emptyList(), val roots: List<CatalogRoot> = emptyList(), val shelves: List<UserShelf> = emptyList())

/** Итог добавления найденных файлов в библиотеку. */
data class CatalogImportResult(val added: Int, val failed: List<String>)

/** Состояние сканирования для показа: этап, сколько найдено и обработано. */
data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val found: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    val message: String? = null
)

/** Полки каталога по образцу интернет-магазинов. */
enum class ShelfKind(val label: String) {
    ALL("Все"),
    RECENT("Недавние"),
    AUTHOR("Авторы"),
    GENRE("Жанры"),
    YEAR("Годы"),
    SERIES("Серии"),
    FORMAT("Форматы"),
    FOLDER("Папки"),
    LANGUAGE("Языки"),
    MINE("Мои подборки")
}

enum class CatalogSort(val label: String) {
    TITLE("Название"), AUTHOR("Автор"), YEAR("Год (новые)"), ADDED("Недавно найденные"), SIZE("Размер"), MODIFIED("Изменён")
}

/** Значение полки и число книг в нём. */
data class ShelfEntry(val value: String, val count: Int)

/** Выбранный фильтр: поиск по тексту + значение полки. */
data class CatalogFilter(
    val query: String = "",
    val kind: ShelfKind = ShelfKind.ALL,
    val value: String? = null,
    val sort: CatalogSort = CatalogSort.TITLE,
    val hideAdded: Boolean = false
)

/** Группировка и фильтрация каталога — чистые функции, без платформы. */
object CatalogQuery {

    const val NO_VALUE = "Не указано"
    private const val RECENT_LIMIT = 100

    fun decade(year: Int): String = "${year / 10 * 10}-е"

    private fun valuesOf(item: CatalogItem, kind: ShelfKind): List<String> = when (kind) {
        ShelfKind.AUTHOR -> listOf(item.author.ifBlank { NO_VALUE })
        ShelfKind.GENRE -> item.genres.ifEmpty { listOf(NO_VALUE) }
        ShelfKind.YEAR -> listOf(item.year?.let(::decade) ?: NO_VALUE)
        ShelfKind.SERIES -> listOf(item.series.ifBlank { NO_VALUE })
        ShelfKind.FORMAT -> listOf(item.format.label)
        ShelfKind.FOLDER -> listOf(item.folder.ifBlank { NO_VALUE })
        ShelfKind.LANGUAGE -> listOf(item.language.ifBlank { NO_VALUE })
        else -> emptyList()
    }

    /** Значения полки [kind] с числом книг; пустое «Не указано» уходит в конец, остальное — по убыванию числа книг. */
    fun shelf(items: List<CatalogItem>, kind: ShelfKind, shelves: List<UserShelf> = emptyList()): List<ShelfEntry> {
        if (kind == ShelfKind.MINE) return shelves.map { ShelfEntry(it.name, it.uris.size) }
        val counts = LinkedHashMap<String, Int>()
        for (item in items) for (v in valuesOf(item, kind)) counts[v] = (counts[v] ?: 0) + 1
        return counts.map { ShelfEntry(it.key, it.value) }
            .sortedWith(compareBy<ShelfEntry> { it.value == NO_VALUE }.thenByDescending { it.count }.thenBy { it.value.lowercase() })
    }

    fun filter(items: List<CatalogItem>, f: CatalogFilter, shelves: List<UserShelf> = emptyList()): List<CatalogItem> {
        var result = items
        if (f.hideAdded) result = result.filter { it.libraryId == null }
        when (f.kind) {
            ShelfKind.ALL -> Unit
            ShelfKind.RECENT -> result = result.sortedByDescending { it.foundAt }.take(RECENT_LIMIT)
            ShelfKind.MINE -> {
                val uris = shelves.firstOrNull { it.name == f.value }?.uris?.toHashSet()
                if (f.value != null) result = result.filter { uris != null && it.uri in uris }
            }
            else -> if (f.value != null) result = result.filter { f.value in valuesOf(it, f.kind) }
        }
        val q = f.query.trim().lowercase()
        if (q.isNotEmpty()) {
            result = result.filter { q in it.title.lowercase() || q in it.author.lowercase() || q in it.series.lowercase() || q in it.name.lowercase() }
        }
        // Полка «Недавние» всегда идёт от новых к старым
        val sorted = when (if (f.kind == ShelfKind.RECENT) CatalogSort.ADDED else f.sort) {
            CatalogSort.TITLE -> result.sortedBy { it.title.lowercase() }
            CatalogSort.AUTHOR -> result.sortedWith(compareBy<CatalogItem> { it.author.isBlank() }.thenBy { it.author.lowercase() }.thenBy { it.title.lowercase() })
            CatalogSort.YEAR -> result.sortedWith(compareByDescending<CatalogItem> { it.year ?: 0 }.thenBy { it.title.lowercase() })
            CatalogSort.ADDED -> result.sortedByDescending { it.foundAt }
            CatalogSort.SIZE -> result.sortedByDescending { it.size }
            CatalogSort.MODIFIED -> result.sortedByDescending { it.modified }
        }
        return sorted
    }
}

/** Источник файлов для сканирования (платформа в приложении, подделка в тестах). */
interface ScanSource {
    suspend fun scan(rootId: String, extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int
}

/** Чтение содержимого найденного файла для метаданных. */
fun interface ContentReader {
    suspend fun read(uri: String, maxBytes: Long): ByteArray?
}

/**
 * Каталог книг на устройстве: сканирование выбранных мест, быстрые метаданные из имени и затем из
 * содержимого, хранение результата, пользовательские подборки. Тяжёлая работа идёт в фоне, с прогрессом и отменой.
 */
class CatalogRepository(
    private val source: ScanSource,
    private val reader: ContentReader,
    private val store: NovelFiles,
    private val scope: CoroutineScope,
    private val now: () -> Long = ::nowMillis
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val data = load()

    private val _items = MutableStateFlow(data.items)
    val items: StateFlow<List<CatalogItem>> = _items.asStateFlow()

    private val _roots = MutableStateFlow(data.roots)
    val roots: StateFlow<List<CatalogRoot>> = _roots.asStateFlow()

    private val _shelves = MutableStateFlow(data.shelves)
    val shelves: StateFlow<List<UserShelf>> = _shelves.asStateFlow()

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var job: Job? = null

    private fun load(): CatalogData =
        store.read(FILE)?.let { runCatching { json.decodeFromString(CatalogData.serializer(), it.decodeToString()) }.getOrNull() } ?: CatalogData()

    private fun save() {
        val snapshot = CatalogData(_items.value, _roots.value, _shelves.value)
        store.write(FILE, json.encodeToString(CatalogData.serializer(), snapshot).encodeToByteArray())
    }

    /** Сканирует [rootId] (`device` или выбранная папка): находит файлы, затем читает метаданные. */
    fun scan(rootId: String, title: String, includeText: Boolean) {
        if (_state.value.running) return
        job = scope.launch {
            _state.value = ScanState(running = true, phase = "Ищем файлы")
            try {
                val extensions = if (includeText) BOOK_EXTENSIONS + TEXT_EXTENSIONS else BOOK_EXTENSIONS
                val found = ArrayList<ScannedFile>()
                val viewed = source.scan(rootId, extensions) {
                    found.add(it)
                    if (found.size % 20 == 0) _state.value = _state.value.copy(found = found.size)
                }
                mergeFound(rootId, found)
                _roots.value = (_roots.value.filterNot { it.id == rootId } + CatalogRoot(rootId, title, now(), found.size))
                save()
                _state.value = ScanState(running = true, phase = "Читаем данные книг", found = found.size)
                enrich(rootId)
                _state.value = ScanState(running = false, phase = "Готово", found = found.size, processed = found.size,
                    message = "Найдено книг: ${found.size} (просмотрено файлов: $viewed)")
            } catch (e: CancellationException) {
                save()
                _state.value = _state.value.copy(running = false, phase = "Остановлено", message = "Сканирование остановлено — найденное сохранено")
                throw e
            } catch (e: Exception) {
                save()
                _state.value = ScanState(running = false, phase = "Ошибка", message = "Не удалось просканировать: ${e.message ?: "ошибка доступа"}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun mergeFound(rootId: String, found: List<ScannedFile>) {
        val existing = _items.value.associateBy { it.uri }
        val time = now()
        val merged = found.mapNotNull { f ->
            existing[f.uri]?.let { old -> return@mapNotNull old.copy(size = f.size, modified = f.modified, folder = f.folder) }
            val format = FormatDetector.byName(f.name) ?: return@mapNotNull null
            val meta = BookMetadata.fromName(f.name)
            CatalogItem(f.uri, f.name, f.size, f.modified, f.folder, rootId, format, meta.title, meta.author, meta.genres, meta.year, meta.language, meta.series, foundAt = time)
        }
        // Файлы, исчезнувшие из этого места, из каталога убираются; остальные места не затрагиваются
        _items.value = _items.value.filter { it.root != rootId } + merged
    }

    private suspend fun enrich(rootId: String) {
        val pending = _items.value.filter { it.root == rootId && !it.enriched && it.size in 1..MAX_READ_BYTES && it.format in READABLE }
        pending.forEachIndexed { i, item ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val meta = reader.read(item.uri, MAX_READ_BYTES)?.let { BookMetadata.read(item.name, it) }
            val updated = if (meta != null) item.copy(
                title = meta.title.ifBlank { item.title }, author = meta.author, genres = meta.genres, year = meta.year,
                language = meta.language, series = meta.series, enriched = true
            ) else item.copy(enriched = true)
            _items.update { list -> list.map { if (it.uri == item.uri) updated else it } }
            _state.value = _state.value.copy(processed = i + 1, total = pending.size)
            if (i % 25 == 24) save()
            yield()
        }
        save()
    }

    /** Помечает файл добавленным в библиотеку. */
    fun markAdded(uri: String, libraryId: String) {
        _items.update { list -> list.map { if (it.uri == uri) it.copy(libraryId = libraryId) else it } }
        save()
    }

    fun forgetRoot(rootId: String) {
        _items.value = _items.value.filterNot { it.root == rootId }
        _roots.value = _roots.value.filterNot { it.id == rootId }
        save()
    }

    // ---------- подборки пользователя ----------

    fun createShelf(name: String, uris: List<String>) {
        val clean = name.trim().take(60)
        if (clean.isEmpty() || uris.isEmpty()) return
        val existing = _shelves.value.firstOrNull { it.name == clean }
        _shelves.value = _shelves.value.filterNot { it.name == clean } +
            UserShelf(existing?.id ?: "s${now()}", clean, ((existing?.uris.orEmpty()) + uris).distinct())
        save()
    }

    fun deleteShelf(name: String) {
        _shelves.value = _shelves.value.filterNot { it.name == name }
        save()
    }

    companion object {
        private const val FILE = "catalog.json"
        const val MAX_READ_BYTES = 40L * 1024 * 1024
        val BOOK_EXTENSIONS = setOf("fb2", "fb2.zip", "fbz", "epub", "pdf", "docx", "docm", "dotx", "dotm", "doc", "odt", "rtf", "wps", "pages")
        val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "html", "htm", "xml")
        private val READABLE = setOf(BookFormat.FB2, BookFormat.EPUB, BookFormat.DOCX, BookFormat.ODT, BookFormat.PDF)
    }
}
