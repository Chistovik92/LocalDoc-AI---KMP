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
class LibraryRepository(private val kv: KeyValueStore, private val files: FileAccess) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<BookEntry>> = _entries.asStateFlow()

    suspend fun add(book: Book): BookEntry = withContext(Dispatchers.Default) {
        val existing = _entries.value.firstOrNull { it.source.isNotBlank() && it.source == book.source && it.format == book.format }
        val id = existing?.id ?: newId()
        files.writeAppFile(fileName(id), json.encodeToString(Book.serializer(), book).encodeToByteArray())
        val entry = BookEntry(
            id = id,
            title = book.title,
            author = book.author,
            format = book.format,
            source = book.source,
            totalBlocks = book.blocks.size,
            progressBlock = existing?.progressBlock?.coerceAtMost((book.blocks.size - 1).coerceAtLeast(0)) ?: 0
        )
        update(listOf(entry) + _entries.value.filterNot { it.id == id })
        entry
    }

    suspend fun load(id: String): Book? = withContext(Dispatchers.Default) {
        files.readAppFile(fileName(id))?.let { bytes ->
            runCatching { json.decodeFromString(Book.serializer(), bytes.decodeToString()) }.getOrNull()
        }
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
        files.deleteAppFile(fileName(id))
        update(_entries.value.filterNot { it.id == id })
    }

    private fun update(list: List<BookEntry>) {
        _entries.value = list
        kv.put(KEY_ENTRIES, json.encodeToString(ENTRIES_SERIALIZER, list))
    }

    private fun loadEntries(): List<BookEntry> =
        kv.get(KEY_ENTRIES)?.let { runCatching { json.decodeFromString(ENTRIES_SERIALIZER, it) }.getOrNull() }.orEmpty()

    private fun fileName(id: String) = "$id.json"

    private fun newId(): String = Random.nextLong().toULong().toString(16)

    companion object {
        private const val KEY_ENTRIES = "library_v1"
        private val ENTRIES_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(BookEntry.serializer())
    }
}
