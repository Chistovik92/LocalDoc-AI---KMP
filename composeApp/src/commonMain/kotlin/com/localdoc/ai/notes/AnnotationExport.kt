package com.localdoc.ai.notes

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.chaptersFromHeadings
import com.localdoc.ai.book.export.BookExporter
import com.localdoc.ai.book.export.ExportFormat

/** Что выгружается вместе с цитатами: закладки книги и слова личного словаря из этой книги. */
data class BookNotes(
    val title: String,
    val author: String,
    val annotations: List<Annotation>,
    val bookmarks: List<com.localdoc.ai.book.Bookmark> = emptyList(),
    val words: List<DictionaryEntry> = emptyList()
) {
    val isEmpty: Boolean get() = annotations.isEmpty() && bookmarks.isEmpty() && words.isEmpty()
}

/** Экспорт цитат и заметок: Markdown для заметочников и EPUB для чтения в любой читалке. */
object AnnotationExport {

    private const val NO_CHAPTER = "Без главы"

    /** Цитаты по главам в порядке чтения: `> цитата` и под ней заметка. */
    fun markdown(notes: BookNotes): String = buildString {
        append("# ").append(notes.title.ifBlank { "Без названия" }).append('\n')
        if (notes.author.isNotBlank()) append("*").append(notes.author).append("*\n")
        append('\n')
        for ((chapter, items) in groupByChapter(notes.annotations)) {
            append("## ").append(chapter).append("\n\n")
            for (a in items) {
                a.quote.lines().forEach { append("> ").append(it.trim()).append('\n') }
                if (a.note.isNotBlank()) append('\n').append(a.note.trim()).append('\n')
                append('\n')
            }
        }
        if (notes.bookmarks.isNotEmpty()) {
            append("## Закладки\n\n")
            notes.bookmarks.sortedBy { it.block }.forEach { m ->
                append("- ").append(m.excerpt.ifBlank { "Без текста" })
                if (m.chapter.isNotBlank()) append(" — ").append(m.chapter)
                append('\n')
            }
            append('\n')
        }
        if (notes.words.isNotEmpty()) {
            append("## Слова\n\n")
            notes.words.forEach { w -> append("- **").append(w.word).append("** — ").append(w.meaning.replace('\n', ' ')).append('\n') }
        }
    }.trimEnd() + "\n"

    /** EPUB с теми же записями: каждая глава книги — раздел оглавления. */
    fun epub(notes: BookNotes): ByteArray = BookExporter.export(asBook(notes), ExportFormat.EPUB)

    fun fileName(notes: BookNotes, extension: String): String {
        val base = notes.title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }.joinToString("").trim().take(60)
        return "Заметки — ${base.ifBlank { "книга" }}.$extension"
    }

    /** Заметки в виде книги: заголовок главы, цитаты как «QUOTE», заметки абзацами. */
    internal fun asBook(notes: BookNotes): Book {
        val blocks = ArrayList<Block>()
        for ((chapter, items) in groupByChapter(notes.annotations)) {
            blocks.add(Block(BlockType.HEADING, chapter, level = 1))
            for (a in items) {
                a.quote.lines().filter { it.isNotBlank() }.forEach { blocks.add(Block(BlockType.QUOTE, it.trim())) }
                if (a.note.isNotBlank()) blocks.add(Block(BlockType.PARAGRAPH, a.note.trim()))
            }
        }
        if (notes.bookmarks.isNotEmpty()) {
            blocks.add(Block(BlockType.HEADING, "Закладки", level = 1))
            notes.bookmarks.sortedBy { it.block }.forEach { m ->
                blocks.add(Block(BlockType.LIST_ITEM, m.excerpt.ifBlank { "Без текста" } + m.chapter.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()))
            }
        }
        if (notes.words.isNotEmpty()) {
            blocks.add(Block(BlockType.HEADING, "Слова", level = 1))
            notes.words.forEach { w -> blocks.add(Block(BlockType.LIST_ITEM, "${w.word} — ${w.meaning.replace('\n', ' ')}")) }
        }
        if (blocks.isEmpty()) blocks.add(Block(BlockType.PARAGRAPH, "Заметок пока нет."))
        return Book(
            title = "Заметки — " + notes.title.ifBlank { "книга" },
            author = notes.author,
            format = BookFormat.EPUB,
            blocks = blocks,
            chapters = chaptersFromHeadings(blocks, maxLevel = 1)
        )
    }

    /** Цитаты, сгруппированные по главам в порядке первого появления главы в книге. */
    private fun groupByChapter(annotations: List<Annotation>): List<Pair<String, List<Annotation>>> {
        val sorted = annotations.sortedWith(compareBy({ it.anchor.block }, { it.anchor.start }))
        val groups = LinkedHashMap<String, MutableList<Annotation>>()
        for (a in sorted) groups.getOrPut(a.chapter.ifBlank { NO_CHAPTER }) { ArrayList() }.add(a)
        return groups.map { it.key to it.value }
    }

    /** Личный словарь целиком в Markdown: список «слово — значение» с предложением из книги. */
    fun dictionaryMarkdown(words: List<DictionaryEntry>): String = buildString {
        append("# Мой словарь\n\n")
        words.sortedBy { it.word.lowercase() }.forEach { w ->
            append("- **").append(w.word).append("** — ").append(w.meaning.replace('\n', ' '))
            if (w.context.isNotBlank()) append("\n  > ").append(w.context.replace('\n', ' '))
            if (w.bookTitle.isNotBlank()) append("\n  — ").append(w.bookTitle)
            append('\n')
        }
    }
}
