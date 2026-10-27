package com.localdoc.ai.notes

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.net.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Цвет подсветки цитаты: [argb] — полупрозрачный фон поверх текста. */
@Serializable
enum class AnnotationColor(val label: String, val argb: Long) {
    YELLOW("Жёлтый", 0x66FFD54F),
    GREEN("Зелёный", 0x6681C784),
    BLUE("Синий", 0x6664B5F6),
    PINK("Розовый", 0x66F48FB1),
    ORANGE("Оранжевый", 0x66FFB74D)
}

/**
 * Цитата (выделение) в книге с необязательной заметкой. Место хранится как [TextAnchor] — оно
 * переживает смену шрифта, размера страницы и повторный разбор книги.
 */
@Serializable
data class Annotation(
    val id: String,
    val entryId: String,
    val bookTitle: String,
    val format: BookFormat,
    val anchor: TextAnchor,
    /** Заметка пользователя; пусто — просто выделенная цитата. */
    val note: String = "",
    val createdAt: Long = 0,
    /** Адрес страницы для заметок в веб-главах. */
    val url: String = "",
    val color: AnnotationColor = AnnotationColor.YELLOW
) {
    val quote: String get() = anchor.quote
    val chapter: String get() = anchor.chapter
}

/** Список записей в KV (JSON), порядок — новые сверху. Хранилище передаётся функциями — удобно для тестов. */
internal class JsonListStore<T>(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    elementSerializer: KSerializer<T>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(elementSerializer)
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    fun set(list: List<T>) {
        _items.value = list
        save(json.encodeToString(serializer, list))
    }

    /** Перечитывает список из хранилища (после восстановления из резервной копии). */
    fun reload() {
        _items.value = read()
    }

    private fun read(): List<T> =
        load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}

/** Цитаты и заметки всех книг. */
class AnnotationRepository(load: () -> String?, save: (String) -> Unit) {
    private val store = JsonListStore(load, save, Annotation.serializer())
    val all: StateFlow<List<Annotation>> get() = store.items

    fun forEntry(entryId: String): List<Annotation> =
        store.items.value.filter { it.entryId == entryId }.sortedWith(compareBy({ it.anchor.block }, { it.anchor.start }))

    fun add(entry: BookEntry, anchor: TextAnchor, note: String = "", color: AnnotationColor = AnnotationColor.YELLOW): Annotation {
        val annotation = Annotation(
            id = Random.nextLong().toULong().toString(16),
            entryId = entry.id,
            bookTitle = entry.title,
            format = entry.format,
            anchor = anchor,
            note = note.trim(),
            createdAt = nowMillis(),
            url = if (entry.format == BookFormat.WEB) entry.source else "",
            color = color
        )
        store.set(listOf(annotation) + store.items.value)
        return annotation
    }

    fun updateNote(id: String, note: String) =
        store.set(store.items.value.map { if (it.id == id) it.copy(note = note.trim()) else it })

    fun updateColor(id: String, color: AnnotationColor) =
        store.set(store.items.value.map { if (it.id == id) it.copy(color = color) else it })

    fun remove(id: String) = store.set(store.items.value.filterNot { it.id == id })

    /** Удаляет заметки книги вместе с самой книгой. */
    fun removeForEntry(entryId: String) = store.set(store.items.value.filterNot { it.entryId == entryId })

    fun reload() = store.reload()
}
