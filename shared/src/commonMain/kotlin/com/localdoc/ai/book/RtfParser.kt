package com.localdoc.ai.book

/**
 * Rich Text Format (`.rtf`): читаем потоково, без загрузки в дерево. Сохраняются абзацы, заголовки
 * (стили «heading N» / «Заголовок N», `\outlinelvl`), элементы списков, таблицы (строка → абзац),
 * жирный/курсив/моноширинный текст, метаданные `\info` и встроенные PNG/JPEG. Кодировка символов
 * `\'hh` берётся из `\ansicpg` и `\fcharset` шрифта (кириллица Windows-1251 и Latin-1/1252).
 */
object RtfParser {

    fun isRtf(bytes: ByteArray): Boolean = bytes.size >= 5 && bytes.decodeToString(0, 5) == "{\\rtf"

    fun parse(bytes: ByteArray, fallbackTitle: String): Book {
        require(isRtf(bytes)) { "Это не документ RTF" }
        val reader = RtfReader(bytes.decodeToString())
        reader.run()
        require(reader.blocks.isNotEmpty()) { "В документе RTF не найден текст" }
        val blocks = reader.blocks
        val chapters = chaptersFromHeadings(blocks).ifEmpty { PlainTextParser.detectChapters(blocks) }
        return Book(
            title = reader.title.ifBlank { fallbackTitle },
            author = reader.author,
            format = BookFormat.RTF,
            blocks = blocks,
            chapters = chapters
        ).withImages(reader.images)
    }
}

private enum class Dest { NORMAL, SKIP, FONTTBL, STYLESHEET, INFO, TITLE, AUTHOR, PICT, LISTTEXT }

private class RtfGroup(
    val dest: Dest,
    val bold: Boolean,
    val italic: Boolean,
    val font: Int,
    val uc: Int
) {
    fun with(dest: Dest = this.dest, bold: Boolean = this.bold, italic: Boolean = this.italic, font: Int = this.font, uc: Int = this.uc) =
        RtfGroup(dest, bold, italic, font, uc)
}

private class RtfReader(private val s: String) {

    val blocks = ArrayList<Block>()
    val images = ImageCollector()
    var title = ""
    var author = ""

    private var i = 0
    private val stack = ArrayList<RtfGroup>()
    private var group = RtfGroup(Dest.NORMAL, bold = false, italic = false, font = -1, uc = 1)

    private var defaultCp1251 = false
    private val fontCp1251 = HashSet<Int>()
    private val fontMono = HashSet<Int>()
    private var fontDef = -1
    private val fontName = StringBuilder()
    private var styleDef = -1
    private val styleName = StringBuilder()
    private val styleNames = HashMap<Int, String>()
    private val infoBuf = StringBuilder()

    // Абзац
    private var parStyle = -1
    private var outline = -1
    private var isList = false
    private var listLevel = 0
    private var builder = DocxParser.RunTextBuilder()
    private val pending = StringBuilder()
    private var pendingStyle = Triple(false, false, false)
    private var skipChars = 0

    // Картинка
    private val pictHex = StringBuilder()
    private var pictMime: String? = null

    fun run() {
        var groupStart = false
        while (i < s.length) {
            when (val c = s[i]) {
                '{' -> {
                    stack.add(group)
                    i++
                    groupStart = true
                    // «{\*» — необязательное расширение: пропускаем, кроме картинок, которые нам нужны
                    if (s.startsWith("\\*", i)) {
                        val word = peekWord(i + 2)
                        group = group.with(dest = if (word == "shppict") Dest.NORMAL else Dest.SKIP)
                    }
                    continue
                }
                '}' -> {
                    closeGroup()
                    i++
                }
                '\\' -> control(groupStart)
                '\r', '\n' -> i++
                else -> {
                    text(c)
                    i++
                }
            }
            groupStart = false
        }
        flushParagraph()
    }

    private fun peekWord(from: Int): String {
        var p = from
        if (p < s.length && s[p] == '\\') p++
        val start = p
        while (p < s.length && s[p].isLetter()) p++
        return s.substring(start, p)
    }

    private fun closeGroup() {
        val closed = group
        if (closed.dest == Dest.PICT) finishPicture()
        if (closed.dest == Dest.TITLE) title = infoBuf.toString().trim().also { infoBuf.clear() }
        if (closed.dest == Dest.AUTHOR) author = infoBuf.toString().trim().also { infoBuf.clear() }
        group = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else RtfGroup(Dest.NORMAL, false, false, -1, 1)
    }

    private fun control(groupStart: Boolean) {
        i++ // '\'
        if (i >= s.length) return
        val c = s[i]
        if (!c.isLetter()) {
            i++
            when (c) {
                '\'' -> hexChar()
                '~' -> text(' ')
                '_' -> text('-')
                '\\', '{', '}' -> text(c)
                '\n', '\r' -> endParagraph()
            }
            return
        }
        val start = i
        while (i < s.length && s[i].isLetter()) i++
        val word = s.substring(start, i)
        var param: Int? = null
        val paramStart = i
        if (i < s.length && (s[i] == '-' || s[i].isDigit())) {
            i++
            while (i < s.length && s[i].isDigit()) i++
            param = s.substring(paramStart, i).toIntOrNull()
        }
        if (i < s.length && s[i] == ' ') i++
        word(word, param, groupStart)
    }

    private fun word(word: String, param: Int?, groupStart: Boolean) {
        if (groupStart) {
            val parent = group.dest
            when {
                word == "fonttbl" -> { group = group.with(dest = Dest.FONTTBL); return }
                word == "stylesheet" -> { group = group.with(dest = Dest.STYLESHEET); return }
                word == "info" -> { group = group.with(dest = Dest.INFO); return }
                word == "pict" -> { group = group.with(dest = Dest.PICT); pictHex.clear(); pictMime = null; return }
                word == "listtext" || word == "pntext" -> { group = group.with(dest = Dest.LISTTEXT); isList = true; return }
                parent == Dest.INFO && word == "title" -> { group = group.with(dest = Dest.TITLE); infoBuf.clear(); return }
                parent == Dest.INFO && word == "author" -> { group = group.with(dest = Dest.AUTHOR); infoBuf.clear(); return }
                word in SKIPPED -> { group = group.with(dest = Dest.SKIP); return }
            }
        }
        when (group.dest) {
            Dest.FONTTBL -> { fontWord(word, param); return }
            Dest.STYLESHEET -> { styleWord(word, param); return }
            Dest.PICT -> { pictWord(word); return }
            Dest.TITLE, Dest.AUTHOR -> {
                if (word == "u") unicode(param ?: return) else if (word == "uc") group = group.with(uc = param ?: 1)
                return
            }
            Dest.NORMAL -> Unit
            else -> return
        }
        when (word) {
            "ansicpg" -> defaultCp1251 = param == 1251
            "uc" -> group = group.with(uc = param ?: 1)
            "u" -> unicode(param ?: return)
            "par", "sect" -> endParagraph()
            "pard" -> { parStyle = -1; outline = -1; isList = false; listLevel = 0 }
            "ilvl" -> listLevel = (param ?: 0).coerceIn(0, 8)
            "s" -> parStyle = param ?: -1
            "outlinelvl" -> outline = param ?: -1
            "ls" -> isList = true
            "b" -> group = group.with(bold = (param ?: 1) != 0)
            "i" -> group = group.with(italic = (param ?: 1) != 0)
            "plain" -> group = group.with(bold = false, italic = false)
            "f" -> group = group.with(font = param ?: -1)
            "tab", "line", "page", "emspace", "enspace" -> text(' ')
            "cell", "nestcell" -> text(" | ")
            "row", "nestrow" -> endParagraph()
            "bullet" -> text('•')
            "endash" -> text('–')
            "emdash" -> text('—')
            "lquote" -> text('‘')
            "rquote" -> text('’')
            "ldblquote" -> text('“')
            "rdblquote" -> text('”')
        }
    }

    private fun fontWord(word: String, param: Int?) {
        when (word) {
            "f" -> { fontDef = param ?: -1; fontName.clear() }
            "fcharset" -> if (param == 204) fontCp1251.add(fontDef)
            "fmodern" -> fontMono.add(fontDef)
        }
    }

    private fun styleWord(word: String, param: Int?) {
        if (word == "s") { styleDef = param ?: -1; styleName.clear() }
    }

    private fun hexChar() {
        if (i + 2 > s.length) return
        val value = s.substring(i, i + 2).toIntOrNull(16)
        i += 2
        if (value == null || group.dest == Dest.PICT) return
        if (consumeSkip()) return
        val cyrillic = group.font in fontCp1251 || (defaultCp1251 && fontCp1251.isEmpty())
        val ch = if (cyrillic && value >= 0x80) TextDecoder.decodeCp1251(byteArrayOf(value.toByte()))[0] else DocParser.cp1252(value)
        text(ch.toString(), countsAsFallback = false)
    }

    private fun unicode(raw: Int) {
        val code = if (raw < 0) raw + 65536 else raw
        text(code.toChar().toString(), countsAsFallback = false)
        skipChars = group.uc
    }

    private fun consumeSkip(): Boolean {
        if (skipChars <= 0) return false
        skipChars--
        return true
    }

    private fun text(c: Char) = text(c.toString(), countsAsFallback = true)

    private fun text(str: String, countsAsFallback: Boolean = true) {
        if (countsAsFallback && consumeSkip()) return
        when (group.dest) {
            Dest.NORMAL -> emit(str)
            Dest.TITLE, Dest.AUTHOR -> infoBuf.append(str)
            Dest.FONTTBL -> if (str == ";") {
                if (MONO_FONTS.any { it in fontName.toString().lowercase() }) fontMono.add(fontDef)
                fontName.clear()
            } else fontName.append(str)
            Dest.STYLESHEET -> if (str == ";") {
                styleNames[styleDef] = styleName.toString().trim()
                styleName.clear()
            } else styleName.append(str)
            Dest.PICT -> if (str.length == 1 && (str[0].isDigit() || str[0] in 'a'..'f' || str[0] in 'A'..'F')) pictHex.append(str)
            else -> Unit
        }
    }

    private fun emit(str: String) {
        val style = Triple(group.bold, group.italic, group.font in fontMono)
        if (style != pendingStyle) {
            flushPending()
            pendingStyle = style
        }
        pending.append(str)
    }

    private fun flushPending() {
        if (pending.isNotEmpty()) builder.append(pending.toString(), pendingStyle.first, pendingStyle.second, pendingStyle.third)
        pending.clear()
    }

    private fun endParagraph() {
        if (group.dest == Dest.NORMAL) flushParagraph()
    }

    private fun flushParagraph() {
        flushPending()
        val (raw, spans) = builder.build()
        builder = DocxParser.RunTextBuilder()
        val text = raw.trim().removeSuffix("|").trim()
        if (text.isBlank()) return
        val styleTitle = styleNames[parStyle].orEmpty()
        val level = HEADING_STYLE.find(styleTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: (if (styleTitle.equals("title", true) || styleTitle.equals("название", true)) 1 else null)
            ?: outline.takeIf { it in 0..8 }?.plus(1)
        val lowerStyle = styleTitle.lowercase()
        blocks.add(
            when {
                level != null -> Block(BlockType.HEADING, text, level.coerceIn(1, 6))
                lowerStyle == "quote" || lowerStyle == "цитата" || lowerStyle == "block text" -> Block(BlockType.QUOTE, text, 0, spans.take(MAX_SPANS))
                lowerStyle == "code" || lowerStyle == "preformatted" -> Block(BlockType.CODE, text)
                isList -> Block(BlockType.LIST_ITEM, text, listLevel, spans.take(MAX_SPANS))
                else -> Block(BlockType.PARAGRAPH, text, 0, spans.take(MAX_SPANS))
            }
        )
    }

    private fun pictWord(word: String) {
        when (word) {
            "pngblip" -> pictMime = "image/png"
            "jpegblip" -> pictMime = "image/jpeg"
        }
    }

    private fun finishPicture() {
        val hex = pictHex.toString()
        pictHex.clear()
        if (pictMime == null || hex.length < 32 || hex.length % 2 != 0 || hex.length / 2 > ImageCollector.MAX_IMAGE_BYTES) return
        val bytes = ByteArray(hex.length / 2)
        for (k in bytes.indices) {
            val v = hex.substring(k * 2, k * 2 + 2).toIntOrNull(16) ?: return
            bytes[k] = v.toByte()
        }
        val id = images.add(bytes) ?: return
        flushParagraph()
        blocks.add(Block(BlockType.IMAGE, "", image = id))
    }

    private companion object {
        const val MAX_SPANS = 2_000
        val HEADING_STYLE = Regex("^(?:heading|заголовок)\\s*(\\d)$", RegexOption.IGNORE_CASE)
        val MONO_FONTS = listOf("courier", "consolas", "mono", "menlo", "lucida console")
        val SKIPPED = setOf(
            "colortbl", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
            "footnote", "annotation", "object", "listtable", "listoverridetable", "rsidtbl", "generator",
            "themedata", "colorschememapping", "datastore", "latentstyles", "xmlnstbl", "pnseclvl",
            "fldinst", "bkmkstart", "bkmkend", "nonshppict", "shpinst", "mmathpr", "wgrffmtfilter",
            "docvar", "userprops", "revtbl", "private", "filetbl", "template", "xe", "tc"
        )
    }
}
