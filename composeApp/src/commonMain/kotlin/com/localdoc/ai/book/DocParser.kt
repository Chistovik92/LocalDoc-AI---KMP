package com.localdoc.ai.book

/**
 * Старый бинарный Word (`.doc`, Word 97–2003): OLE2-контейнер, поток `WordDocument` с заголовком FIB
 * и таблица кусков текста (Clx → PlcPcd) в потоке `0Table`/`1Table`.
 * Достаём только основной текст документа — без колонтитулов, сносок и картинок.
 */
object DocParser {

    private const val WORD_MAGIC = 0xA5EC
    private const val FLAG_ENCRYPTED = 0x0100
    private const val FLAG_TABLE_1 = 0x0200
    private const val CLX_PRC = 0x01
    private const val CLX_PCDT = 0x02
    private const val COMPRESSED_BIT = 0x40000000L
    private const val FC_MASK = 0x3FFFFFFFL
    private const val FC_CLX_PAIR_INDEX = 33

    fun parse(bytes: ByteArray, fallbackTitle: String): Book {
        val text = extractText(bytes)
        val blocks = text.lines().map { HtmlBlockBuilder.normalizeSpaces(it) }.filter { it.isNotEmpty() }
            .map { Block(BlockType.PARAGRAPH, it) }
        require(blocks.isNotEmpty()) { "В документе .doc не найден текст" }
        return Book(title = fallbackTitle, format = BookFormat.DOC, blocks = blocks, chapters = PlainTextParser.detectChapters(blocks))
    }

    /** Основной текст документа с символом `\n` на месте каждого абзаца и ячейки таблицы. */
    internal fun extractText(bytes: ByteArray): String =
        try {
            readText(bytes)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Повреждённый документ .doc: данные обрезаны")
        }

    private fun readText(bytes: ByteArray): String {
        val ole = try {
            Ole2File(bytes)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(e.message ?: "Не удалось прочитать документ .doc")
        }
        val word = ole.stream("WordDocument") ?: throw IllegalArgumentException("Это не документ Word: нет потока WordDocument")
        require(word.size > 0x1A8 && u16(word, 0) == WORD_MAGIC) { "Это не документ Word 97–2003" }
        val flags = u16(word, 0x0A)
        require(flags and FLAG_ENCRYPTED == 0) { "Документ защищён паролем — откройте его в Word и сохраните без пароля" }
        val table = ole.stream(if (flags and FLAG_TABLE_1 != 0) "1Table" else "0Table")
            ?: throw IllegalArgumentException("Повреждённый документ: нет таблицы текста")

        // FIB: csw слов FibRgW97, затем cslw длинных слов FibRgLw97 (в нём ccpText), затем пары fc/lcb
        val csw = u16(word, 0x20)
        val lwStart = 0x20 + 2 + csw * 2 + 2
        val cslw = u16(word, lwStart - 2)
        require(cslw >= 4 && lwStart + 16 <= word.size) { "Повреждённый документ: заголовок FIB" }
        val ccpText = s32(word, lwStart + 12)
        val fcLcbStart = lwStart + cslw * 4 + 2
        val clxOffset = fcLcbStart + FC_CLX_PAIR_INDEX * 8
        require(clxOffset + 8 <= word.size) { "Повреждённый документ: нет описания кусков текста" }
        val fcClx = s32(word, clxOffset)
        val lcbClx = s32(word, clxOffset + 4)
        require(ccpText > 0 && fcClx >= 0 && lcbClx > 0 && fcClx.toLong() + lcbClx <= table.size) {
            "В документе нет основного текста"
        }
        return renderText(decodePieces(word, table, fcClx, lcbClx, ccpText))
    }

    private fun decodePieces(word: ByteArray, table: ByteArray, fcClx: Int, lcbClx: Int, ccpText: Int): String {
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end && (table[p].toInt() and 0xFF) == CLX_PRC) {
            p += 3 + u16(table, p + 1)
        }
        require(p + 5 <= end && (table[p].toInt() and 0xFF) == CLX_PCDT) { "Повреждённый документ: нет таблицы кусков текста" }
        val plcSize = s32(table, p + 1)
        val plc = p + 5
        require(plcSize >= 4 && plc + plcSize <= table.size) { "Повреждённый документ: таблица кусков текста" }
        val pieces = (plcSize - 4) / 12
        val out = StringBuilder()
        for (i in 0 until pieces) {
            val cpStart = s32(table, plc + i * 4)
            val cpEnd = minOf(s32(table, plc + (i + 1) * 4), ccpText)
            if (cpStart >= ccpText) break
            if (cpEnd <= cpStart) continue
            val raw = u32(table, plc + (pieces + 1) * 4 + i * 8 + 2)
            val compressed = raw and COMPRESSED_BIT != 0L
            val fc = raw and FC_MASK
            val length = cpEnd - cpStart
            if (compressed) {
                val from = (fc / 2).toInt()
                require(from >= 0 && from + length <= word.size) { "Повреждённый документ: кусок текста за пределами файла" }
                for (k in 0 until length) out.append(cp1252(word[from + k].toInt() and 0xFF))
            } else {
                val from = fc.toInt()
                require(from >= 0 && from + length * 2 <= word.size) { "Повреждённый документ: кусок текста за пределами файла" }
                for (k in 0 until length) out.append(u16(word, from + k * 2).toChar())
            }
        }
        return out.toString()
    }

    /** Служебные символы Word → абзацы: 0x0D/0x07 — конец абзаца или ячейки; поля — только результат. */
    private fun renderText(raw: String): String {
        val out = StringBuilder(raw.length)
        // Стек полей: true — мы внутри кода поля (его текст пропускаем), false — внутри результата
        val fields = ArrayList<Boolean>()
        for (c in raw) {
            when (c.code) {
                0x13 -> fields.add(true)
                0x14 -> if (fields.isNotEmpty()) fields[fields.lastIndex] = false
                0x15 -> if (fields.isNotEmpty()) fields.removeAt(fields.lastIndex)
                else -> {
                    if (fields.any { it }) continue
                    when (c.code) {
                        0x0D, 0x07, 0x0C, 0x0E -> out.append('\n')
                        0x0B, 0x09, 0xA0 -> out.append(' ')
                        0x1E -> out.append('-')
                        in 0x00..0x1F -> Unit
                        else -> out.append(c)
                    }
                }
            }
        }
        return out.toString()
    }

    private fun cp1252(b: Int): Char = if (b in 0x80..0x9F) CP1252_HIGH[b - 0x80] else b.toChar()

    // Символы 0x80..0x9F кодовой страницы Windows-1252 (не определённые — пробел)
    private val CP1252_HIGH: CharArray = "€ ‚ƒ„…†‡ˆ‰Š‹Œ Ž  ‘’“”•–—˜™š›œ žŸ".toCharArray()
        .also { check(it.size == 32) { "Таблица cp1252 должна содержать 32 символа" } }

    private fun u16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun s32(b: ByteArray, p: Int): Int = u16(b, p) or (u16(b, p + 2) shl 16)
    private fun u32(b: ByteArray, p: Int): Long = s32(b, p).toLong() and 0xFFFFFFFFL
}
