package com.localdoc.ai.sync

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.Bookmark
import com.localdoc.ai.catalog.CatalogItem
import com.localdoc.ai.catalog.UserShelf
import com.localdoc.ai.notes.Annotation
import com.localdoc.ai.storage.KvAccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Идентификатор книги, одинаковый на всех устройствах: хеш формата и источника (идентификатор записи библиотеки
 * на каждом устройстве свой). Книги без источника не синхронизируются. Сам путь в записи не попадает.
 */
fun bookKey(entry: BookEntry): String? {
    if (entry.source.isBlank()) return null
    var h = -0x340d631b7bdddcdbL // FNV-1a 64
    for (b in "${entry.format.name}|${entry.source}".encodeToByteArray()) {
        h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
    }
    return h.toULong().toString(16)
}

private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private val entries = ListSerializer(BookEntry.serializer())

private const val LIBRARY_KEY = "library_v1"

private fun libraryOf(kv: KvAccess): List<BookEntry> =
    kv.getValue(LIBRARY_KEY)?.let { runCatching { storeJson.decodeFromString(entries, it) }.getOrNull() }.orEmpty()

@Serializable
private class ProgressPayload(val block: Int)

/** Прогресс чтения по книгам. Удаление книги прогресс в облаке не удаляет. */
class ProgressCollection(private val kv: KvAccess) : SyncCollection {
    override val kind = "progress"
    override val tracksDeletion = false

    override fun read(): Map<String, String> = libraryOf(kv).mapNotNull { e ->
        bookKey(e)?.let { it to wire.encodeToString(ProgressPayload.serializer(), ProgressPayload(e.progressBlock)) }
    }.toMap()

    override fun apply(upserts: Map<String, String>, removals: Set<String>): Set<String> {
        val library = libraryOf(kv)
        val known = library.mapNotNull(::bookKey).toSet()
        val updated = library.map { e ->
            val payload = bookKey(e)?.let { upserts[it] } ?: return@map e
            val block = wire.decodeFromString(ProgressPayload.serializer(), payload).block
            e.copy(progressBlock = block.coerceIn(0, (e.totalBlocks - 1).coerceAtLeast(0)))
        }
        if (updated != library) kv.putValue(LIBRARY_KEY, storeJson.encodeToString(entries, updated))
        return upserts.keys.filterNot { it in known }.toSet()
    }
}

/**
 * Список записей в KV, привязанных к книге (закладки, цитаты). В облако уходит копия без локального entryId;
 * ключ — «ключКниги/идентификаторЗаписи».
 */
class BookLinkedCollection<T>(
    override val kind: String,
    private val kv: KvAccess,
    private val storeKey: String,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val entryIdOf: (T) -> String,
    private val withEntryId: (T, String) -> T
) : SyncCollection {
    override val tracksDeletion = true
    private val list = ListSerializer(serializer)

    private fun items(): List<T> =
        kv.getValue(storeKey)?.let { runCatching { storeJson.decodeFromString(list, it) }.getOrNull() }.orEmpty()

    override fun read(): Map<String, String> {
        val keys = libraryOf(kv).mapNotNull { e -> bookKey(e)?.let { e.id to it } }.toMap()
        val result = LinkedHashMap<String, String>()
        for (item in items()) {
            val book = keys[entryIdOf(item)] ?: continue
            result["$book/${idOf(item)}"] = wire.encodeToString(serializer, withEntryId(item, ""))
        }
        return result
    }

    override fun apply(upserts: Map<String, String>, removals: Set<String>): Set<String> {
        val byKey = libraryOf(kv).mapNotNull { e -> bookKey(e)?.let { it to e.id } }.toMap()
        var current = items()
        val pending = HashSet<String>()
        for (key in removals) {
            val book = byKey[key.substringBefore('/')] ?: continue
            val id = key.substringAfter('/')
            current = current.filterNot { idOf(it) == id && entryIdOf(it) == book }
        }
        for ((key, payload) in upserts) {
            val entryId = byKey[key.substringBefore('/')]
            if (entryId == null) {
                pending += key
                continue
            }
            val item = withEntryId(wire.decodeFromString(serializer, payload), entryId)
            val id = idOf(item)
            val at = current.indexOfFirst { idOf(it) == id && entryIdOf(it) == entryId }
            current = if (at >= 0) current.toMutableList().also { it[at] = item } else listOf(item) + current
        }
        kv.putValue(storeKey, storeJson.encodeToString(list, current))
        return pending
    }

    companion object {
        fun bookmarks(kv: KvAccess) = BookLinkedCollection(
            "bookmark", kv, "bookmarks_v1", Bookmark.serializer(), { it.id }, { it.entryId }, { b, e -> b.copy(entryId = e) }
        )

        fun annotations(kv: KvAccess) = BookLinkedCollection(
            "annotation", kv, "annotations_v1", Annotation.serializer(), { it.id }, { it.entryId }, { a, e -> a.copy(entryId = e) }
        )
    }
}

@Serializable
private class ShelfPayload(val name: String, val books: List<String>)

/**
 * Подборки пользователя. Файлы подборки — пути устройства, поэтому синхронизируются название и книги из
 * библиотеки (по [bookKey]); файлы, которых нет в библиотеке, остаются только на своём устройстве. Подборка,
 * ссылающаяся на книгу, которой на этом устройстве нет, ждёт её появления.
 */
class ShelfCollection(
    private val shelves: () -> List<UserShelf>,
    private val setShelves: (List<UserShelf>) -> Unit,
    private val catalogItems: () -> List<CatalogItem>,
    private val library: () -> List<BookEntry>
) : SyncCollection {
    override val kind = "shelf"
    override val tracksDeletion = true

    override fun read(): Map<String, String> {
        val keyByLibraryId = library().mapNotNull { e -> bookKey(e)?.let { e.id to it } }.toMap()
        val keyByUri = catalogItems().mapNotNull { i -> i.libraryId?.let { keyByLibraryId[it] }?.let { i.uri to it } }.toMap()
        return shelves().associate { s ->
            s.id to wire.encodeToString(
                ShelfPayload.serializer(),
                ShelfPayload(s.name, s.uris.mapNotNull { keyByUri[it] }.distinct().sorted())
            )
        }
    }

    override fun apply(upserts: Map<String, String>, removals: Set<String>): Set<String> {
        val entryByKey = library().mapNotNull { e -> bookKey(e)?.let { it to e.id } }.toMap()
        val urisByLibraryId = catalogItems().filter { it.libraryId != null }.groupBy({ it.libraryId!! }, { it.uri })
        val libraryUris = urisByLibraryId.values.flatten().toSet()
        var current = shelves().filterNot { it.id in removals }
        val pending = HashSet<String>()
        for ((id, payload) in upserts) {
            val p = wire.decodeFromString(ShelfPayload.serializer(), payload)
            val ids = p.books.map { entryByKey[it] }
            if (ids.any { it == null }) {
                pending += id
                continue
            }
            val old = current.firstOrNull { it.id == id }
            val localOnly = old?.uris.orEmpty().filter { it !in libraryUris }
            val uris = (ids.flatMap { urisByLibraryId[it].orEmpty() } + localOnly).distinct()
            val shelf = UserShelf(id, p.name, uris)
            current = if (old == null) current + shelf else current.map { if (it.id == id) shelf else it }
        }
        setShelves(current)
        return pending
    }
}
