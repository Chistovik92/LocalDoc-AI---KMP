package com.localdoc.ai.book

/** Элемент полки библиотеки: одиночная книга или группа глав одной веб-новеллы. */
sealed interface LibraryItem {
    data class Single(val entry: BookEntry) : LibraryItem

    /** [chapters] отсортированы по номеру главы, [mostRecent] — последняя открытая. */
    data class Novel(val title: String, val host: String, val chapters: List<BookEntry>, val mostRecent: BookEntry) : LibraryItem
}

/** Стабильный ключ элемента полки для списков и открытой шторки. */
val LibraryItem.shelfKey: String
    get() = when (this) {
        is LibraryItem.Single -> "book:${entry.id}"
        is LibraryItem.Novel -> "novel:$host|${title.lowercase()}"
    }

private const val SEPARATORS = " \\t,.:;–—-"
private const val MIN_NOVEL_TITLE = 3

/** «… 16. Глава 16: Название», «… - Chapter 12», «…, том 2, часть 3» — хвост, который отделяет название новеллы. */
private val CHAPTER_SUFFIX = Regex(
    "(?:^|[$SEPARATORS]+)(?:(?:том|volume|vol\\.?)\\s*\\d+[$SEPARATORS]*)?(?:\\d+[$SEPARATORS]+)?(?:глава|chapter|ch\\.?|часть|part)(?![\\p{L}\\d]).*",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private val FIRST_NUMBER = Regex("\\d+")

/** Название новеллы без номера и заголовка главы; null — запись не похожа на главу новеллы. */
fun BookEntry.novelTitle(): String? {
    if (format != BookFormat.WEB) return null
    val trimmed = title.trim()
    val match = CHAPTER_SUFFIX.find(trimmed) ?: return null
    val base = trimmed.substring(0, match.range.first).trim().trimEnd(',', '.', ':', ';', '–', '—', '-', ' ')
    return base.takeIf { it.length >= MIN_NOVEL_TITLE }
}

/** Заголовок главы без названия новеллы и дублирующего номера: «Глава 16: Название». */
fun BookEntry.chapterLabel(): String {
    val novel = novelTitle() ?: return title
    return title.trim().removePrefix(novel).trimStart(' ', ',', '.', ':', ';', '–', '—', '-')
        .replaceFirst(LEADING_NUMBER, "").ifBlank { title }
}

private val LEADING_NUMBER = Regex("^\\d+[.:\\s]+(?=\\p{L})")

private fun BookEntry.host(): String = source.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()

private fun BookEntry.chapterNumber(): Int {
    val tail = CHAPTER_SUFFIX.find(title.trim())?.value.orEmpty()
    return FIRST_NUMBER.find(tail)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}

/**
 * Собирает главы одной веб-новеллы (тот же сайт и то же название) в одну группу.
 * Порядок полки сохраняется: группа стоит там, где её самая недавняя глава.
 */
fun groupLibrary(entries: List<BookEntry>): List<LibraryItem> {
    fun keyOf(entry: BookEntry): String? = entry.novelTitle()?.let { "${entry.host()}|${it.lowercase()}" }

    val sizes = entries.mapNotNull(::keyOf).groupingBy { it }.eachCount()
    val emitted = mutableSetOf<String>()
    return buildList {
        for (entry in entries) {
            val key = keyOf(entry)
            when {
                key == null || (sizes[key] ?: 0) < 2 -> add(LibraryItem.Single(entry))
                emitted.add(key) -> {
                    val chapters = entries.filter { keyOf(it) == key }
                    add(
                        LibraryItem.Novel(
                            title = checkNotNull(entry.novelTitle()),
                            host = entry.host(),
                            chapters = chapters.sortedWith(compareBy<BookEntry> { it.chapterNumber() }.thenBy { it.title }),
                            mostRecent = entry
                        )
                    )
                }
            }
        }
    }
}
