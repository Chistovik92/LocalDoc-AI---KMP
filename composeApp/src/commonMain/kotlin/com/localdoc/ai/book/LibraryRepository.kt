package com.localdoc.ai.book

import com.localdoc.ai.platform.FileAccess
import com.localdoc.ai.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Библиотека: список записей (KV, JSON) + кэш разобранных книг в файлах приложения.
 * Порядок записей — недавние сверху. Повторный импорт того же источника обновляет запись.
 */
class LibraryRepository(
    private val kv: KeyValueStore,
    private val files: FileAccess,
    /** Содержимое книги заменено повторным импортом: производные данные (поисковый индекс) устарели. */
    private val onContentReplaced: (String) -> Unit = {}
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<BookEntry>> = _entries.asStateFlow()

    suspend fun add(book: Book): BookEntry = withContext(Dispatchers.Default) {
        val existing = _entries.value.firstOrNull { it.source.isNotBlank() && it.source == book.source && it.format == book.format }
        val id = existing?.id ?: newId()
        files.writeAppFile(fileName(id), json.encodeToString(Book.serializer(), book).encodeToByteArray())
        // Повторный импорт заменяет картинки книги целиком: старые файлы не накапливаются
        if (existing != null) files.deleteAppFiles(imagePrefix(id))
        var imageBytes = 0L
        for ((imageId, data) in book.imageData) {
            files.writeAppFile(imagePrefix(id) + imageId, data)
            imageBytes += data.size
        }
        val entry = BookEntry(
            id = id,
            title = book.title,
            author = book.author,
            format = book.format,
            source = book.source,
            totalBlocks = book.blocks.size,
            progressBlock = existing?.progressBlock?.coerceAtMost((book.blocks.size - 1).coerceAtLeast(0)) ?: 0,
            cover = book.cover,
            imageBytes = imageBytes
        )
        update(listOf(entry) + _entries.value.filterNot { it.id == id })
        if (existing != null) onContentReplaced(id)
        entry
    }

    suspend fun load(id: String): Book? = withContext(Dispatchers.Default) {
        files.readAppFile(fileName(id))?.let { bytes ->
            runCatching { json.decodeFromString(Book.serializer(), bytes.decodeToString()) }.getOrNull()
        }
    }

    /** Книга вместе с байтами её картинок — для экспорта и конвертации. */
    suspend fun loadWithImages(id: String): Book? = withContext(Dispatchers.Default) {
        val book = load(id) ?: return@withContext null
        val data = HashMap<String, ByteArray>()
        for (imageId in book.images) files.readAppFile(imagePrefix(id) + imageId)?.let { data[imageId] = it }
        book.copy(imageData = data)
    }

    /** Заменяет оглавление книги; текст, картинки, прогресс и закладки остаются прежними. Возвращает обновлённую книгу. */
    suspend fun replaceChapters(id: String, chapters: List<Chapter>): Book? = withContext(Dispatchers.Default) {
        val book = load(id) ?: return@withContext null
        val updated = book.copy(chapters = chapters.sortedBy { it.startBlock })
        files.writeAppFile(fileName(id), json.encodeToString(Book.serializer(), updated).encodeToByteArray())
        updated
    }

    /**
     * Заменяет текст книги исправленным ([TextRepair]): оглавление и прогресс переносятся на новые номера блоков,
     * картинки и обложка остаются. Закладки и цитаты ищут место по тексту, поэтому не теряются. Возвращает книгу
     * и новое место чтения.
     */
    suspend fun replaceBlocks(id: String, result: RepairResult): Pair<Book, Int>? = withContext(Dispatchers.Default) {
        val book = load(id) ?: return@withContext null
        val entry = _entries.value.firstOrNull { it.id == id }
        val updated = book.copy(blocks = result.blocks, chapters = TextRepair.remapChapters(book.chapters, result))
        files.writeAppFile(fileName(id), json.encodeToString(Book.serializer(), updated).encodeToByteArray())
        val progress = result.newIndex(entry?.progressBlock ?: 0).coerceIn(0, (result.blocks.size - 1).coerceAtLeast(0))
        if (entry != null) {
            update(_entries.value.map { if (it.id == id) entry.copy(totalBlocks = result.blocks.size, progressBlock = progress) else it })
        }
        onContentReplaced(id)
        updated to progress
    }

    fun entry(id: String): BookEntry? = _entries.value.firstOrNull { it.id == id }

    /** Отметка «открыта сейчас»: поднимает книгу наверх списка. */
    fun touch(id: String) {
        val entry = entry(id) ?: return
        update(listOf(entry) + _entries.value.filterNot { it.id == id })
    }

    fun saveProgress(id: String, block: Int) {
        val list = _entries.value
        if (list.none { it.id == id && it.progressBlock != block }) return
        update(list.map { if (it.id == id) it.copy(progressBlock = block) else it })
    }

    fun remove(id: String) {
        // Копия PDF, по которой рисуются страницы, лежит отдельно от картинок книги
        files.readAppFile(fileName(id))
            ?.let { runCatching { json.decodeFromString(Book.serializer(), it.decodeToString()) }.getOrNull() }
            ?.pdfFile?.let(files::deleteAppFile)
        files.deleteAppFile(fileName(id))
        files.deleteAppFiles(imagePrefix(id))
        update(_entries.value.filterNot { it.id == id })
    }

    /** Перечитывает список из хранилища (после восстановления из резервной копии). */
    fun reload() {
        _entries.value = loadEntries()
    }

    private fun update(list: List<BookEntry>) {
        _entries.value = list
        kv.put(KEY_ENTRIES, json.encodeToString(ENTRIES_SERIALIZER, list))
    }

    private fun loadEntries(): List<BookEntry> =
        kv.get(KEY_ENTRIES)?.let { runCatching { json.decodeFromString(ENTRIES_SERIALIZER, it) }.getOrNull() }.orEmpty()

    private fun fileName(id: String) = "$id.json"

    private fun imagePrefix(id: String) = "$id.img."

    private fun newId(): String = Random.nextLong().toULong().toString(16)

    companion object {
        private const val KEY_ENTRIES = "library_v1"
        private val ENTRIES_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(BookEntry.serializer())
    }
}
