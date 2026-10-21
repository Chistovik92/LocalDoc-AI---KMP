package com.localdoc.ai.book.export

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book

/**
 * RTF: таблица шрифтов, стили «heading N», «Quote», «Code», маркированные списки (`\ls`), выделения,
 * метаданные `\info`, встроенные PNG/JPEG (`\pngblip` / `\jpegblip`). Весь не-ASCII текст пишется
 * как `\uN?`, поэтому файл читается любым редактором независимо от кодовой страницы.
 */
internal object RtfWriter {

    private const val TWIPS_PER_PIXEL = 15
    private const val MAX_WIDTH_TWIPS = 8800

    private const val STYLE_QUOTE = 7
    private const val STYLE_CODE = 8

    fun write(book: Book): ByteArray {
        val images = book.exportImages()
        val out = StringBuilder()
        out.append("{\\rtf1\\ansi\\ansicpg1252\\deff0\\uc1")
        out.append("{\\fonttbl{\\f0\\froman\\fcharset0 Times New Roman;}{\\f1\\fmodern\\fcharset0 Courier New;}}")
        out.append("{\\colortbl;}")
        out.append("{\\stylesheet{\\s0 Normal;}")
        for (n in 1..6) out.append("{\\s").append(n).append("\\sbasedon0\\snext0 heading ").append(n).append(";}")
        out.append("{\\s$STYLE_QUOTE Quote;}{\\s$STYLE_CODE Code;}}")
        out.append("{\\info{\\title ").append(escape(book.title)).append("}{\\author ").append(escape(book.author)).append("}}\n")
        out.append("\\viewkind4\\f0\\fs24\n")
        for (block in book.blocks) {
            when (block.type) {
                BlockType.HEADING -> {
                    val level = block.level.coerceIn(1, 6)
                    val size = (40 - level * 4).coerceAtLeast(24)
                    out.append("\\pard\\s").append(level).append("\\outlinelvl").append(level - 1).append("\\sb240\\sa120\\keepn\\b\\fs").append(size).append(' ')
                        .append(runs(block, base = "\\b")).append("\\b0\\fs24\\par\n")
                }
                BlockType.PARAGRAPH -> out.append("\\pard\\sa120 ").append(runs(block)).append("\\par\n")
                BlockType.QUOTE -> out.append("\\pard\\s").append(STYLE_QUOTE).append("\\li720\\sa120\\i ").append(runs(block, base = "\\i")).append("\\i0\\par\n")
                BlockType.CODE -> out.append("\\pard\\s").append(STYLE_CODE).append("\\sa120\\f1\\fs20 ").append(codeText(block.text)).append("\\f0\\fs24\\par\n")
                BlockType.LIST_ITEM -> {
                    val indent = 720 * (block.level.coerceIn(0, 8) + 1)
                    out.append("\\pard\\ls1\\ilvl").append(block.level.coerceIn(0, 8)).append("\\li").append(indent).append("\\fi-360\\sa40 {\\listtext\\u8226?\\tab}")
                        .append(runs(block)).append("\\par\n")
                }
                BlockType.IMAGE -> block.image?.let { images[it] }?.let { appendPicture(out, it) }
            }
        }
        out.append("}")
        return out.toString().encodeToByteArray()
    }

    private fun runs(block: Block, base: String = ""): String = block.runs().joinToString("") { run ->
        val flags = (if (run.bold && base != "\\b") "\\b" else "") + (if (run.italic && base != "\\i") "\\i" else "") + (if (run.code) "\\f1" else "")
        if (flags.isEmpty()) escape(run.text) else "{" + flags + " " + escape(run.text) + "}"
    }

    private fun codeText(text: String) = text.lines().joinToString("\\line ") { escape(it) }

    private fun appendPicture(out: StringBuilder, image: ExportImage) {
        val kind = when (image.mime) {
            "image/png" -> "\\pngblip"
            "image/jpeg" -> "\\jpegblip"
            else -> return // GIF, WebP и BMP в RTF не встраиваются
        }
        val size = image.size
        var w = (size?.width ?: 600) * TWIPS_PER_PIXEL
        var h = (size?.height ?: 400) * TWIPS_PER_PIXEL
        if (w > MAX_WIDTH_TWIPS) { h = h * MAX_WIDTH_TWIPS / w; w = MAX_WIDTH_TWIPS }
        out.append("\\pard\\qc{\\pict").append(kind).append("\\picwgoal").append(w).append("\\pichgoal").append(h).append('\n')
        val hex = "0123456789abcdef"
        var column = 0
        for (b in image.bytes) {
            val v = b.toInt() and 0xFF
            out.append(hex[v shr 4]).append(hex[v and 15])
            if (++column == 40) { out.append('\n'); column = 0 }
        }
        out.append("}\\par\n")
    }

    /** `\`, `{`, `}` экранируются; всё за пределами ASCII — `\uN?` (знаковое 16-битное число). */
    internal fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\\' || c == '{' || c == '}' -> out.append('\\').append(c)
                c == '\t' -> out.append("\\tab ")
                c == '\n' -> out.append("\\line ")
                c.code in 0x20..0x7E -> out.append(c)
                c.code < 0x20 -> Unit
                else -> {
                    val signed = if (c.code > 32767) c.code - 65536 else c.code
                    out.append("\\u").append(signed).append('?')
                }
            }
        }
        return out.toString()
    }
}
