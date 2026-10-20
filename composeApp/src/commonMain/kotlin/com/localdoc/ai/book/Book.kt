package com.localdoc.ai.book

import com.localdoc.ai.parser.ParsedDoc
import kotlinx.serialization.Serializable

/** Поддерживаемые форматы книг и документов. */
@Serializable
enum class BookFormat(val label: String) {
    PDF("PDF"),
    EPUB("EPUB"),
    FB2("FB2"),
    MARKDOWN("Markdown"),
    TEXT("TXT"),
    HTML("HTML"),
    WEB("Веб"),
    DOCX("DOCX"),
    DOC("DOC"),
    ODT("ODT")
}

/** Тип блока текста — единица вёрстки, озвучки и прогресса чтения. */
@Serializable
enum class BlockType { HEADING, PARAGRAPH, QUOTE, CODE, LIST_ITEM }

/** Инлайн-выделение внутри блока (диапазон [start, end) в символах). */
@Serializable
data class Span(val start: Int, val end: Int, val style: SpanStyleType)

@Serializable
enum class SpanStyleType { BOLD, ITALIC, CODE }

@Serializable
data class Block(
    val type: BlockType,
    val text: String,
    /** Уровень заголовка (1..6) или вложенность списка. */
    val level: Int = 0,
    val spans: List<Span> = emptyList()
)

/** Пункт оглавления: заголовок и индекс первого блока главы. */
@Serializable
data class Chapter(val title: String, val startBlock: Int)

/** Разобранная книга в едином формате для всех источников. */
@Serializable
data class Book(
    val title: String,
    val author: String = "",
    val format: BookFormat,
    val source: String = "",
    val blocks: List<Block>,
    val chapters: List<Chapter> = emptyList(),
    /** Для веб-страниц: соседние главы и оглавление новеллы. */
    val web: WebNavigation? = null
) {
    val plainText: String get() = blocks.joinToString("\n\n") { it.text }

    /** Индекс главы, в которую попадает блок (-1, если до первой главы). */
    fun chapterIndexAt(blockIndex: Int): Int =
        chapters.indexOfLast { it.startBlock <= blockIndex }

    fun toParsedDoc(): ParsedDoc = ParsedDoc(
        title = title,
        content = plainText,
        source = source,
        pageCount = if (format == BookFormat.PDF) chapters.size.coerceAtLeast(1) else 1
    )
}

/** Запись библиотеки: метаданные и прогресс без содержимого книги. */
@Serializable
data class BookEntry(
    val id: String,
    val title: String,
    val author: String = "",
    val format: BookFormat,
    val source: String = "",
    val totalBlocks: Int,
    val progressBlock: Int = 0
) {
    val progress: Float
        get() = if (totalBlocks <= 1) 0f else (progressBlock.toFloat() / (totalBlocks - 1)).coerceIn(0f, 1f)
}

/** Построение оглавления по заголовкам, если в источнике его нет. */
internal fun chaptersFromHeadings(blocks: List<Block>, maxLevel: Int = 2): List<Chapter> =
    blocks.mapIndexedNotNull { index, block ->
        if (block.type == BlockType.HEADING && block.level <= maxLevel && block.text.isNotBlank()) {
            Chapter(block.text.take(120), index)
        } else null
    }
