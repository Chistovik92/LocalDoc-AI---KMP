package com.localdoc.ai.book

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Закладка в книге, документе или сохранённой веб-странице (глава веб-новеллы — тоже запись библиотеки).
 * [block] — блок, к которому вернёмся; [excerpt] — короткая выдержка для списка.
 */
@Serializable
data class Bookmark(
    val id: String,
    val entryId: String,
    val bookTitle: String,
    val format: BookFormat,
    val block: Int,
    val excerpt: String,
    /** Адрес страницы для веб-закладок (открывается из кэша, а при его отсутствии — по сети). */
    val url: String = "",
    /** Название главы/раздела на момент создания закладки. */
    val chapter: String = ""
)

/** Закладки: список в KV (JSON), порядок — новые сверху. Хранилище передаётся функциями — удобно для тестов. */
class BookmarkRepository(
    private val load: () -> String?,
    private val save: (String) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Bookmark.serializer())
    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Bookmark>> = _all.asStateFlow()

    fun forEntry(entryId: String): List<Bookmark> = _all.value.filter { it.entryId == entryId }.sortedBy { it.block }

    /** Закладка, стоящая в блоках [range] (например, на текущей странице). */
    fun findIn(entryId: String, range: IntRange): Bookmark? =
        _all.value.firstOrNull { it.entryId == entryId && it.block in range }

    /** Добавляет закладку; если на этом блоке она уже есть — возвращает существующую. */
    fun add(entry: BookEntry, block: Int, text: String, chapter: String = ""): Bookmark {
        _all.value.firstOrNull { it.entryId == entry.id && it.block == block }?.let { return it }
        val bookmark = Bookmark(
            id = Random.nextLong().toULong().toString(16),
            entryId = entry.id,
            bookTitle = entry.title,
            format = entry.format,
            block = block,
            excerpt = excerptOf(text),
            url = if (entry.format == BookFormat.WEB) entry.source else "",
            chapter = chapter
        )
        update(listOf(bookmark) + _all.value)
        return bookmark
    }

    fun remove(id: String) = update(_all.value.filterNot { it.id == id })

    /** Закладка снимается, если уже стоит на странице, иначе ставится. Возвращает true, если закладка поставлена. */
    fun toggle(entry: BookEntry, range: IntRange, block: Int, text: String, chapter: String = ""): Boolean {
        val existing = findIn(entry.id, range)
        if (existing != null) {
            remove(existing.id)
            return false
        }
        add(entry, block, text, chapter)
        return true
    }

    /** Удаляет закладки книги вместе с самой книгой. */
    fun removeForEntry(entryId: String) = update(_all.value.filterNot { it.entryId == entryId })

    private fun update(list: List<Bookmark>) {
        _all.value = list
        save(json.encodeToString(serializer, list))
    }

    private fun read(): List<Bookmark> =
        load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    companion object {
        private const val EXCERPT_CHARS = 90

        fun excerptOf(text: String): String {
            val clean = text.trim().replace(Regex("\\s+"), " ")
            return if (clean.length <= EXCERPT_CHARS) clean else clean.take(EXCERPT_CHARS).trimEnd() + "…"
        }
    }
}
