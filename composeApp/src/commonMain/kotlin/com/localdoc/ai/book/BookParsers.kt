package com.localdoc.ai.book

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import kotlin.io.encoding.Base64

/** TXT: абзацы по пустым строкам, мягкие переносы строк склеиваются. */
object PlainTextParser {
    fun parse(text: String, title: String): Book {
        val blocks = splitParagraphs(text).map { Block(BlockType.PARAGRAPH, it) }
        return Book(title = title, format = BookFormat.TEXT, blocks = blocks, chapters = detectTextChapters(blocks))
    }

    internal fun splitParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val byBlank = normalized.split(Regex("\n[ \\t]*\n+"))
        // Если пустых строк нет, абзацем считается каждая строка (типично для книг в TXT)
        val parts = if (byBlank.size <= 1 && normalized.count { it == '\n' } > 3) normalized.split('\n') else byBlank
        return parts.map { HtmlBlockBuilder.normalizeSpaces(it.replace(CONTROL_CHARS, "")) }.filter { it.isNotEmpty() }
    }

    // Управляющие символы (NUL и др.) — признак бинарного мусора; в тексте книги им не место
    private val CONTROL_CHARS = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    private val CHAPTER_LINE = Regex("^(глава|часть|chapter|part|пролог|эпилог|prologue|epilogue)\\b.{0,80}$", RegexOption.IGNORE_CASE)

    internal fun detectChapters(blocks: List<Block>): List<Chapter> = detectTextChapters(blocks)

    private fun detectTextChapters(blocks: List<Block>): List<Chapter> =
        blocks.mapIndexedNotNull { i, b -> if (b.text.length <= 90 && CHAPTER_LINE.matches(b.text)) Chapter(b.text, i) else null }
}

/** PDF: текст постранично (маркеры «--- [Страница N] ---»), переносы строк превращаются в абзацы. */
object PdfTextAdapter {
    private val PAGE_MARKER = Regex("""--- \[Страница (\d+)[^\]]*] ---""")

    fun toBook(title: String, content: String, source: String): Book {
        val blocks = ArrayList<Block>()
        val chapters = ArrayList<Chapter>()
        val markers = PAGE_MARKER.findAll(content).toList()
        if (markers.isEmpty()) {
            reflow(content).forEach { blocks.add(Block(BlockType.PARAGRAPH, it)) }
        } else {
            markers.forEachIndexed { i, m ->
                val end = if (i + 1 < markers.size) markers[i + 1].range.first else content.length
                val pageText = content.substring(m.range.last + 1, end)
                chapters.add(Chapter("Страница ${m.groupValues[1]}", blocks.size))
                reflow(pageText).forEach { blocks.add(Block(BlockType.PARAGRAPH, it)) }
            }
        }
        return Book(title = title, format = BookFormat.PDF, source = source, blocks = blocks, chapters = chapters)
    }

    /** Склейка строк PDF в абзацы: перенос со словом «сло-\nво», короткая строка с точкой — конец абзаца. */
    internal fun reflow(text: String): List<String> {
        val lines = text.lines().map { it.trim() }
        if (lines.isEmpty()) return emptyList()
        val avg = lines.filter { it.isNotEmpty() }.map { it.length }.average().takeIf { !it.isNaN() } ?: 0.0
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val t = HtmlBlockBuilder.normalizeSpaces(current.toString())
            if (t.isNotEmpty()) result.add(t)
            current.clear()
        }
        for (line in lines) {
            if (line.isEmpty()) { flush(); continue }
            if (current.endsWith("-") && line.first().isLowerCase()) {
                current.setLength(current.length - 1)
                current.append(line)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(line)
            }
            val endsSentence = line.last() in ".!?:…»\""
            if (endsSentence && line.length < avg * 0.75) flush()
        }
        flush()
        return result
    }
}

/** Markdown: заголовки, списки, цитаты, блоки кода и инлайн **жирный** / *курсив* / `код`. */
object MarkdownParser {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val IMAGE_LINE = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)$")

    fun parse(text: String, title: String): Book {
        val blocks = ArrayList<Block>()
        val paragraph = StringBuilder()
        var inCode = false
        val code = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks.add(inlineBlock(BlockType.PARAGRAPH, paragraph.toString().trim()))
            paragraph.clear()
        }

        for (raw in text.replace("\r\n", "\n").lines()) {
            if (raw.trimStart().startsWith("```")) {
                if (inCode) {
                    blocks.add(Block(BlockType.CODE, code.toString().trimEnd()))
                    code.clear()
                } else flushParagraph()
                inCode = !inCode
                continue
            }
            if (inCode) { code.appendLine(raw); continue }
            val line = raw.trimEnd()
            val imageLine = IMAGE_LINE.matchEntire(line.trim())
            if (imageLine != null) {
                flushParagraph()
                val url = imageLine.groupValues[2]
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    blocks.add(Block(BlockType.IMAGE, imageLine.groupValues[1].trim(), image = url))
                }
                continue
            }
            val heading = HEADING.find(line)
            val list = LIST.find(line)
            when {
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    blocks.add(inlineBlock(BlockType.HEADING, heading.groupValues[2], heading.groupValues[1].length))
                }
                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    blocks.add(inlineBlock(BlockType.QUOTE, line.trimStart().trimStart('>').trim()))
                }
                list != null -> {
                    flushParagraph()
                    blocks.add(inlineBlock(BlockType.LIST_ITEM, list.groupValues[3], list.groupValues[1].length / 2))
                }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(line.trim()) -> flushParagraph()
                else -> paragraph.append(line.trim()).append(' ')
            }
        }
        if (inCode && code.isNotEmpty()) blocks.add(Block(BlockType.CODE, code.toString().trimEnd()))
        flushParagraph()

        val docTitle = blocks.firstOrNull { it.type == BlockType.HEADING && it.level == 1 }?.text ?: title
        return Book(title = docTitle, format = BookFormat.MARKDOWN, blocks = blocks, chapters = chaptersFromHeadings(blocks))
    }

    /** Снимает разметку `**`, `*`, `_`, `` ` ``, ссылки `[текст](url)` и запоминает выделения. */
    internal fun inlineBlock(type: BlockType, source: String, level: Int = 0): Block {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        var i = 0
        val s = source.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        while (i < s.length) {
            val marker = when {
                s.startsWith("**", i) || s.startsWith("__", i) -> s.substring(i, i + 2)
                s[i] == '`' || s[i] == '*' || s[i] == '_' -> s[i].toString()
                else -> null
            }
            val close = marker?.let { s.indexOf(it, i + it.length) } ?: -1
            // `_` внутри слов (snake_case) не считается курсивом
            val wordInner = marker == "_" && i > 0 && s[i - 1].isLetterOrDigit()
            if (marker != null && close > i + marker.length && !wordInner) {
                val inner = s.substring(i + marker.length, close)
                val style = when (marker) {
                    "`" -> SpanStyleType.CODE
                    "**", "__" -> SpanStyleType.BOLD
                    else -> SpanStyleType.ITALIC
                }
                spans.add(Span(out.length, out.length + inner.length, style))
                out.append(inner)
                i = close + marker.length
            } else {
                out.append(s[i])
                i++
            }
        }
        return Block(type, out.toString(), level, spans)
    }
}

/** HTML-файл: та же очистка, что и для веб-страниц (Readability + фильтр рекламы). */
object HtmlFileParser {
    fun parse(html: String, title: String, source: String): Book {
        val doc = Ksoup.parse(html, source)
        val docTitle = doc.title().ifBlank { title }
        val cover = PageCover.find(doc, source)
        val images = ImageCollector()
        val blocks = ArticleExtractor.extract(doc, HtmlImageResolver(source, images))
        return Book(title = docTitle, format = BookFormat.HTML, source = source, blocks = blocks, chapters = chaptersFromHeadings(blocks))
            .withImages(images, cover)
    }
}

/** FictionBook 2: XML со структурой body → section → title / p / poem / epigraph. */
object Fb2Parser {

    fun parse(bytes: ByteArray, fallbackTitle: String): Book {
        val xml = TextDecoder.decode(bytes, TextDecoder.xmlDeclaredEncoding(bytes))
        val doc = Ksoup.parse(xml, Parser.xmlParser(), "")
        val titleInfo = doc.getElementsByTag("title-info").firstOrNull()
        val title = titleInfo?.getElementsByTag("book-title")?.firstOrNull()?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = titleInfo?.getElementsByTag("author")?.firstOrNull()?.let { a ->
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { tag -> a.getElementsByTag(tag).firstOrNull()?.text()?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString(" ")
                .ifBlank { a.getElementsByTag("nickname").firstOrNull()?.text().orEmpty() }
        }.orEmpty()

        val images = ImageCollector()
        val binaries = doc.getElementsByTag("binary").associateBy { it.attr("id") }
        val imageFor: (String) -> String? = { href ->
            binaries[href.removePrefix("#")]?.let { bin ->
                runCatching { Base64.Mime.decode(bin.text()) }.getOrNull()?.let(images::add)
            }
        }
        val cover = titleInfo?.getElementsByTag("coverpage")?.firstOrNull()?.getElementsByTag("image")?.firstOrNull()
            ?.let(::hrefOf)?.let(imageFor)

        val blocks = ArrayList<Block>()
        val chapters = ArrayList<Chapter>()
        // Первый body — текст книги; body name="notes" — сноски, их не читаем подряд
        val body = doc.getElementsByTag("body").firstOrNull { it.attr("name") != "notes" }
        body?.let { walkSection(it, depth = 0, blocks, chapters, imageFor) }
        return Book(title = title, author = author, format = BookFormat.FB2, blocks = blocks, chapters = chapters)
            .withImages(images, cover)
    }

    private fun hrefOf(el: Element): String? = el.attributes().firstOrNull { it.key.endsWith("href") }?.value

    private fun walkSection(
        el: Element,
        depth: Int,
        blocks: MutableList<Block>,
        chapters: MutableList<Chapter>,
        imageFor: (String) -> String?
    ) {
        for (child in el.children()) {
            when (child.normalName()) {
                "title" -> {
                    val text = child.children().joinToString(". ") { it.text().trim() }.ifBlank { child.text() }.trim()
                    if (text.isNotEmpty()) {
                        if (depth <= 2) chapters.add(Chapter(text.take(120), blocks.size))
                        blocks.add(Block(BlockType.HEADING, text, (depth + 1).coerceIn(1, 6)))
                    }
                }
                "section" -> walkSection(child, depth + 1, blocks, chapters, imageFor)
                "p" -> addText(blocks, BlockType.PARAGRAPH, child.text())
                "subtitle" -> addText(blocks, BlockType.HEADING, child.text(), level = (depth + 2).coerceIn(2, 6))
                "epigraph", "cite", "annotation" -> child.children().forEach { addText(blocks, BlockType.QUOTE, it.text()) }
                "poem" -> child.getElementsByTag("stanza").forEach { stanza ->
                    addText(blocks, BlockType.QUOTE, stanza.getElementsByTag("v").joinToString("\n") { it.text().trim() })
                }
                "text-author" -> addText(blocks, BlockType.QUOTE, child.text())
                "empty-line" -> Unit
                "image" -> hrefOf(child)?.let(imageFor)?.let { blocks.add(Block(BlockType.IMAGE, child.attr("alt").trim(), image = it)) }
                else -> walkSection(child, depth, blocks, chapters, imageFor)
            }
        }
    }

    private fun addText(blocks: MutableList<Block>, type: BlockType, text: String, level: Int = 0) {
        val clean = if (type == BlockType.QUOTE) text.trim() else HtmlBlockBuilder.normalizeSpaces(text)
        if (clean.isNotEmpty()) blocks.add(Block(type, clean, level))
    }
}

/** EPUB 2/3: container.xml → OPF (метаданные, manifest, spine) → главы XHTML; оглавление из NCX / nav. */
object EpubParser {

    fun parse(zip: ZipArchive, fallbackTitle: String): Book {
        val container = zip.readText("META-INF/container.xml") ?: error("EPUB без META-INF/container.xml")
        val opfPath = Ksoup.parse(container, Parser.xmlParser(), "")
            .getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotBlank() } ?: error("EPUB: не найден OPF-файл")
        val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opf = Ksoup.parse(zip.readText(opfPath) ?: error("EPUB: OPF не читается"), Parser.xmlParser(), "")

        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = opf.getElementsByTag("dc:creator").joinToString(", ") { it.text().trim() }

        data class Item(val href: String, val mediaType: String, val properties: String)
        val manifest = opf.getElementsByTag("item").associate { item ->
            item.attr("id") to Item(resolveZipPath(opfDir, item.attr("href")), item.attr("media-type"), item.attr("properties"))
        }
        val spine = opf.getElementsByTag("itemref").mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType.contains("html") }

        val images = ImageCollector()
        val blocks = ArrayList<Block>()
        val startByPath = HashMap<String, Int>()
        for (item in spine) {
            val html = zip.read(item.href)?.let { TextDecoder.decode(it, TextDecoder.xmlDeclaredEncoding(it)) } ?: continue
            startByPath[item.href] = blocks.size
            val body = Ksoup.parse(html, "").body() ?: continue
            val dir = item.href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            blocks.addAll(HtmlBlockBuilder(ImageResolver { src -> zipImage(zip, dir, src, images) }).build(body))
        }

        val coverId = opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverItem = coverId?.let { manifest[it] } ?: manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }
        val cover = coverItem?.takeIf { it.mediaType.startsWith("image") }
            ?.let { runCatching { zip.read(it.href) }.getOrNull() }?.let(images::add)

        val tocItems = readNav(zip, manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.href)
            ?: readNcx(zip, manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.href)
        val chapters = tocItems.orEmpty()
            .mapNotNull { (label, path) -> startByPath[path]?.let { Chapter(label, it) } }
            .distinctBy { it.startBlock }
            .ifEmpty { chaptersFromHeadings(blocks) }
        return Book(title = title, author = author, format = BookFormat.EPUB, blocks = blocks, chapters = chapters)
            .withImages(images, cover)
    }

    private fun readNcx(zip: ZipArchive, path: String?): List<Pair<String, String>>? {
        path ?: return null
        val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val ncx = Ksoup.parse(zip.readText(path) ?: return null, Parser.xmlParser(), "")
        return ncx.getElementsByTag("navPoint").mapNotNull { point ->
            val label = point.getElementsByTag("text").firstOrNull()?.text()?.trim() ?: return@mapNotNull null
            val src = point.getElementsByTag("content").firstOrNull()?.attr("src") ?: return@mapNotNull null
            label to resolveZipPath(dir, src)
        }.takeIf { it.isNotEmpty() }
    }

    private fun readNav(zip: ZipArchive, path: String?): List<Pair<String, String>>? {
        path ?: return null
        val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val nav = Ksoup.parse(zip.readText(path) ?: return null, "")
        val tocNav = nav.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: nav.select("nav").firstOrNull()
        return tocNav?.select("a[href]")?.map { it.text().trim() to resolveZipPath(dir, it.attr("href")) }
            ?.filter { it.first.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }
}
