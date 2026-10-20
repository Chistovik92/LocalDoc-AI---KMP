package com.localdoc.ai.book

/**
 * `.wps` — два разных формата под одним расширением:
 *  - WPS Writer (Kingsoft) и совместимые: OLE2 с потоком `WordDocument` — читаются как Word 97–2003;
 *  - Microsoft Works: OLE2 с потоком `CONTENTS` — достаём печатный текст из потока.
 * Если ни того, ни другого нет, пользователь получает понятную ошибку.
 */
object WpsParser {

    fun parse(bytes: ByteArray, fallbackTitle: String): Book {
        val ole = try {
            Ole2File(bytes)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Повреждённый файл WPS: ${e.message}")
        }
        if (ole.hasStream("WordDocument")) {
            return DocParser.parse(bytes, fallbackTitle).copy(format = BookFormat.WPS)
        }
        val contents = ole.stream("CONTENTS")
            ?: throw IllegalArgumentException(UNSUPPORTED)
        val blocks = printableRuns(contents).map { Block(BlockType.PARAGRAPH, it) }
        require(blocks.isNotEmpty()) { UNSUPPORTED }
        return Book(title = fallbackTitle, format = BookFormat.WPS, blocks = blocks, chapters = PlainTextParser.detectChapters(blocks))
    }

    /** Подряд идущие печатные байты (≥ 24 символов подряд) — текст документа Works. */
    internal fun printableRuns(data: ByteArray): List<String> {
        val runs = ArrayList<ByteArray>()
        var start = -1
        fun close(end: Int) {
            if (start >= 0 && end - start >= MIN_RUN) runs.add(data.copyOfRange(start, end))
            start = -1
        }
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            val printable = b >= 0x20 && b != 0x7F || b == 0x0D || b == 0x0A || b == 0x09
            if (printable) { if (start < 0) start = i } else close(i)
        }
        close(data.size)
        val highBytes = runs.sumOf { r -> r.count { (it.toInt() and 0xFF) >= 0xC0 } }
        val total = runs.sumOf { it.size }.coerceAtLeast(1)
        val cyrillic = highBytes * 100 / total >= 20
        return runs.flatMap { r ->
            val text = if (cyrillic) TextDecoder.decodeCp1251(r) else r.map { DocParser.cp1252(it.toInt() and 0xFF) }.joinToString("")
            text.split('\r', '\n').map { HtmlBlockBuilder.normalizeSpaces(it) }.filter { it.isNotEmpty() }
        }
    }

    private const val MIN_RUN = 24
    private const val UNSUPPORTED =
        "Этот вариант формата WPS не поддерживается. Откройте файл в WPS Office или Word и сохраните как DOCX, DOC или TXT."
}
