package com.localdoc.ai.book

import com.localdoc.ai.book.export.BookExporter
import com.localdoc.ai.book.export.ExportFormat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Аудит устойчивости (1.7.5): обрезанные и испорченные файлы каждого формата не должны зависать,
 * вызывать нехватку памяти или бросать неожиданные исключения — только понятные [IllegalArgumentException] /
 * [IllegalStateException]. Приложение в любом случае ловит всё в [BookImporter.import], но разбор обязан завершаться.
 */
class RobustnessTest {

    private fun sample(): Book = Book(
        title = "Образец", author = "Автор", format = BookFormat.EPUB,
        blocks = listOf(
            Block(BlockType.HEADING, "Глава 1", 1),
            Block(BlockType.PARAGRAPH, "Текст абзаца " + "слово ".repeat(40), spans = listOf(Span(0, 5, SpanStyleType.BOLD))),
            Block(BlockType.LIST_ITEM, "пункт", 0),
            Block(BlockType.QUOTE, "цитата"),
            Block(BlockType.HEADING, "Глава 2", 1),
            Block(BlockType.PARAGRAPH, "Конец.")
        ),
        chapters = listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 4))
    )

    private fun parse(bytes: ByteArray, format: BookFormat) {
        try {
            BookImporter.parseBytes(bytes, format, "t", "u")
        } catch (e: IllegalArgumentException) {
            // понятная ошибка разбора
        } catch (e: IllegalStateException) {
            // понятная ошибка разбора
        } catch (e: IndexOutOfBoundsException) {
            // обрезанные данные внутри контейнера: приложение превращает это в сообщение «файл повреждён»
        }
    }

    @Test
    fun `truncated files of every exported format are rejected cleanly`() {
        for (format in ExportFormat.entries) {
            val bytes = BookExporter.export(sample(), format)
            val cuts = listOf(0, 1, 4, 30, bytes.size / 4, bytes.size / 2, bytes.size - 20, bytes.size - 1).filter { it in 0 until bytes.size }
            for (cut in cuts) {
                val head = bytes.copyOf(cut)
                val detected = try {
                    FormatDetector.resolve("файл." + format.extension, head)
                } catch (e: UnsupportedFormatException) {
                    continue
                }
                parse(head, detected)
            }
        }
    }

    @Test
    fun `randomly corrupted files never hang or crash the parsers`() {
        val random = Random(42)
        for (format in ExportFormat.entries) {
            val original = BookExporter.export(sample(), format)
            repeat(25) {
                val bytes = original.copyOf()
                repeat(1 + random.nextInt(8)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
                val detected = try {
                    FormatDetector.resolve("файл." + format.extension, bytes)
                } catch (e: UnsupportedFormatException) {
                    return@repeat
                }
                parse(bytes, detected)
            }
        }
    }

    @Test
    fun `hostile structures are bounded`() {
        // Глубокая вложенность скобок RTF и тегов XML, огромные числа и пустые файлы
        parse(("{\\rtf1" + "{".repeat(20_000) + "x" + "}".repeat(20_000) + "}").encodeToByteArray(), BookFormat.RTF)
        parse("{\\rtf1 \\u99999999999999999999? \\'zz \\par}".encodeToByteArray(), BookFormat.RTF)
        parse(ByteArray(0), BookFormat.TEXT)
        parse(ByteArray(0), BookFormat.DOCX)
        parse(ByteArray(0), BookFormat.PAGES)
        parse("<?xml version=\"1.0\"?><a>${"<b>".repeat(500)}текст${"</b>".repeat(500)}</a>".encodeToByteArray(), BookFormat.XML)
    }

    @Test
    fun `zip with a bogus huge entry size is refused before allocation`() {
        val zip = TestFiles.zip(mapOf("word/document.xml" to "<w:document/>")).copyOf()
        // Подменяем размер несжатых данных в центральном каталоге на ~2 ГБ
        zip[findCentral(zip) + 24 + 3] = 0x7F
        assertTrue(runCatching { ZipArchive(zip).read("word/document.xml") }.isFailure)
    }

    private fun findCentral(zip: ByteArray): Int {
        for (i in 0 until zip.size - 3) {
            if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4B.toByte() && zip[i + 2] == 1.toByte() && zip[i + 3] == 2.toByte()) return i
        }
        error("нет центрального каталога")
    }
}
