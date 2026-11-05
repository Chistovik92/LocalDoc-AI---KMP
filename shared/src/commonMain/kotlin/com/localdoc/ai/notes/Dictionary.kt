package com.localdoc.ai.notes

import com.localdoc.ai.net.nowMillis
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Слово из личного словаря: перевод или определение и предложение, в котором оно встретилось. */
@Serializable
data class DictionaryEntry(
    val id: String,
    val word: String,
    /** Перевод или определение (как ответила модель либо как отредактировал пользователь). */
    val meaning: String,
    /** Предложение из книги, где слово встретилось. */
    val context: String = "",
    val entryId: String = "",
    val bookTitle: String = "",
    /** Код языка перевода, если [meaning] — перевод (пусто — определение). */
    val language: String = "",
    val createdAt: Long = 0
)

/** Личный словарь: повторное сохранение того же слова обновляет запись, а не плодит дубли. Новые сверху. */
class DictionaryRepository(load: () -> String?, save: (String) -> Unit) {
    private val store = JsonListStore(load, save, DictionaryEntry.serializer())
    val all: StateFlow<List<DictionaryEntry>> get() = store.items

    fun find(word: String): DictionaryEntry? {
        val key = normalize(word)
        return store.items.value.firstOrNull { normalize(it.word) == key }
    }

    fun save(
        word: String,
        meaning: String,
        context: String = "",
        entryId: String = "",
        bookTitle: String = "",
        language: String = ""
    ): DictionaryEntry {
        val clean = word.trim()
        require(clean.isNotEmpty()) { "Пустое слово" }
        val existing = find(clean)
        val entry = DictionaryEntry(
            id = existing?.id ?: Random.nextLong().toULong().toString(16),
            word = existing?.word ?: clean,
            meaning = meaning.trim(),
            context = context.trim().ifBlank { existing?.context.orEmpty() },
            entryId = entryId.ifBlank { existing?.entryId.orEmpty() },
            bookTitle = bookTitle.ifBlank { existing?.bookTitle.orEmpty() },
            language = language,
            createdAt = nowMillis()
        )
        store.set(listOf(entry) + store.items.value.filterNot { it.id == entry.id })
        return entry
    }

    fun remove(id: String) = store.set(store.items.value.filterNot { it.id == id })

    fun reload() = store.reload()

    private fun normalize(word: String) = word.trim().lowercase().replace('ё', 'е')
}

/**
 * Слово, выделенное в тексте: из выделения убираются знаки по краям, а для запроса к модели берётся
 * предложение вокруг него — по контексту слово переводится точнее.
 */
object WordSelection {

    private const val MAX_WORD_CHARS = 60

    /** Слово или короткое выражение из выделения; null — выделен длинный фрагмент или нет букв. */
    fun wordOf(selected: String): String? {
        val trimmed = selected.trim().trim { !it.isLetterOrDigit() }
        if (trimmed.isEmpty() || trimmed.length > MAX_WORD_CHARS || trimmed.contains('\n')) return null
        return trimmed
    }

    /** Предложение блока [text], в котором стоит [word] (первое вхождение); весь блок, если границ не видно. */
    fun sentenceAround(text: String, word: String): String {
        val at = text.indexOf(word)
        if (at < 0) return text.trim()
        val start = (text.lastIndexOfAny(SENTENCE_ENDS, at - 1) + 1).coerceAtLeast(0)
        val end = text.indexOfAny(SENTENCE_ENDS, at + word.length).let { if (it < 0) text.length else it + 1 }
        return text.substring(start, end).trim()
    }

    private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '…', '\n')
}
