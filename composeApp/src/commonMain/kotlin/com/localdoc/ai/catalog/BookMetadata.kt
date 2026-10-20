package com.localdoc.ai.catalog

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.FormatDetector
import com.localdoc.ai.book.TextDecoder
import com.localdoc.ai.book.ZipArchive

/** Описание книги для каталога: то, по чему строятся полки и фильтры. */
data class Meta(
    val title: String,
    val author: String = "",
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val language: String = "",
    val series: String = ""
)

/**
 * Метаданные книг без полного разбора текста: заголовок FB2, OPF у EPUB, свойства документа DOCX/ODT,
 * словарь Info у PDF; для остальных — разбор имени файла («Автор - Название (2019).txt»).
 */
object BookMetadata {

    private val YEAR = Regex("(?<![0-9])(1[5-9][0-9]{2}|20[0-4][0-9])(?![0-9])")
    private val BRACKET_YEAR = Regex("[\\[(]\\s*(1[5-9][0-9]{2}|20[0-4][0-9])\\s*[\\])]")
    private val AUTHOR_TITLE = Regex("^(.{2,60}?)\\s+[-–—]\\s+(.+)$")

    /** Метаданные по имени файла — мгновенно, до чтения содержимого. */
    fun fromName(fileName: String): Meta {
        val base = fileName.substringBeforeLast('.').removeSuffix(".fb2").replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val year = YEAR.findAll(base).lastOrNull()?.value?.toIntOrNull()
        val withoutYear = base.replace(BRACKET_YEAR, "").replace(Regex("\\s+"), " ").trim()
        val match = AUTHOR_TITLE.matchEntire(withoutYear)
        return if (match != null && match.groupValues[1].split(' ').size <= 4) {
            Meta(title = match.groupValues[2].trim(), author = match.groupValues[1].trim(), year = year)
        } else {
            Meta(title = withoutYear.ifBlank { base }, year = year)
        }
    }

    /** Метаданные из содержимого; при любом сбое возвращаются данные из имени файла. */
    fun read(fileName: String, bytes: ByteArray): Meta {
        val byName = fromName(fileName)
        val extracted = try {
            when (FormatDetector.byName(fileName)) {
                BookFormat.FB2 -> fb2(bytes)
                BookFormat.EPUB -> epub(bytes)
                BookFormat.DOCX -> docx(bytes)
                BookFormat.ODT -> odt(bytes)
                BookFormat.PDF -> pdf(bytes)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        return merge(extracted, byName)
    }

    private fun merge(primary: Meta?, fallback: Meta): Meta {
        if (primary == null) return fallback
        return Meta(
            title = primary.title.ifBlank { fallback.title },
            author = primary.author.ifBlank { fallback.author },
            genres = primary.genres,
            year = primary.year ?: fallback.year,
            language = primary.language,
            series = primary.series
        )
    }

    // ---------- FB2 ----------

    private fun fb2(bytes: ByteArray): Meta? {
        val raw = if (ZipArchive.isZip(bytes)) {
            val zip = ZipArchive(bytes)
            zip.read(zip.names.firstOrNull { it.endsWith(".fb2", true) } ?: return null) ?: return null
        } else bytes
        // Заголовок книги всегда в начале файла — большую книгу целиком не разбираем
        val head = raw.copyOf(minOf(raw.size, FB2_HEAD))
        val xml = TextDecoder.decode(head, TextDecoder.xmlDeclaredEncoding(head))
        val end = xml.indexOf("</title-info>")
        val source = if (end >= 0) xml.substring(0, end + "</title-info>".length) else xml
        val info = Ksoup.parse(source, Parser.xmlParser(), "").getElementsByTag("title-info").firstOrNull() ?: return null
        val author = info.getElementsByTag("author").firstOrNull()?.let { a ->
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { tag -> a.getElementsByTag(tag).firstOrNull()?.text()?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString(" ").ifBlank { a.getElementsByTag("nickname").firstOrNull()?.text().orEmpty() }
        }.orEmpty()
        return Meta(
            title = info.getElementsByTag("book-title").firstOrNull()?.text()?.trim().orEmpty(),
            author = author,
            genres = info.getElementsByTag("genre").map { Genres.fromCode(it.text()) }.filter { it.isNotEmpty() }.distinct(),
            year = info.getElementsByTag("date").firstOrNull()?.let { d -> YEAR.find(d.attr("value").ifBlank { d.text() })?.value?.toIntOrNull() },
            language = info.getElementsByTag("lang").firstOrNull()?.text()?.trim()?.lowercase().orEmpty(),
            series = info.getElementsByTag("sequence").firstOrNull()?.attr("name").orEmpty()
        )
    }

    // ---------- EPUB ----------

    private fun epub(bytes: ByteArray): Meta? {
        val zip = ZipArchive(bytes)
        val container = zip.readText("META-INF/container.xml") ?: return null
        val opfPath = Ksoup.parse(container, Parser.xmlParser(), "").getElementsByTag("rootfile").firstOrNull()?.attr("full-path") ?: return null
        val opf = Ksoup.parse(zip.readText(opfPath) ?: return null, Parser.xmlParser(), "")
        val series = opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "calibre:series" }?.attr("content")
            ?: opf.getElementsByTag("meta").firstOrNull { it.attr("property") == "belongs-to-collection" }?.text()
        return Meta(
            title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim().orEmpty(),
            author = opf.getElementsByTag("dc:creator").joinToString(", ") { it.text().trim() },
            genres = opf.getElementsByTag("dc:subject").flatMap { splitGenres(it.text()) }.distinct(),
            year = opf.getElementsByTag("dc:date").firstOrNull()?.let { YEAR.find(it.text())?.value?.toIntOrNull() },
            language = opf.getElementsByTag("dc:language").firstOrNull()?.text()?.trim()?.lowercase()?.substringBefore('-').orEmpty(),
            series = series?.trim().orEmpty()
        )
    }

    // ---------- DOCX / ODT ----------

    private fun docx(bytes: ByteArray): Meta? {
        val core = Ksoup.parse(ZipArchive(bytes).readText("docProps/core.xml") ?: return null, Parser.xmlParser(), "")
        return Meta(
            title = core.getElementsByTag("dc:title").firstOrNull()?.text()?.trim().orEmpty(),
            author = core.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim().orEmpty(),
            genres = (core.getElementsByTag("dc:subject") + core.getElementsByTag("cp:keywords")).flatMap { splitGenres(it.text()) }.distinct(),
            year = core.getElementsByTag("dcterms:created").firstOrNull()?.let { YEAR.find(it.text())?.value?.toIntOrNull() },
            language = core.getElementsByTag("dc:language").firstOrNull()?.text()?.trim()?.lowercase()?.substringBefore('-').orEmpty()
        )
    }

    private fun odt(bytes: ByteArray): Meta? {
        val meta = Ksoup.parse(ZipArchive(bytes).readText("meta.xml") ?: return null, Parser.xmlParser(), "")
        return Meta(
            title = meta.getElementsByTag("dc:title").firstOrNull()?.text()?.trim().orEmpty(),
            author = meta.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim().orEmpty(),
            genres = (meta.getElementsByTag("dc:subject") + meta.getElementsByTag("meta:keyword")).flatMap { splitGenres(it.text()) }.distinct(),
            year = meta.getElementsByTag("meta:creation-date").firstOrNull()?.let { YEAR.find(it.text())?.value?.toIntOrNull() },
            language = meta.getElementsByTag("dc:language").firstOrNull()?.text()?.trim()?.lowercase()?.substringBefore('-').orEmpty()
        )
    }

    // ---------- PDF ----------

    /** Словарь Info PDF: строки в скобках (PDFDocEncoding) или `<FEFF…>` (UTF-16BE). Берётся из начала и конца файла. */
    private fun pdf(bytes: ByteArray): Meta? {
        val head = bytes.copyOf(minOf(bytes.size, PDF_WINDOW))
        val tail = bytes.copyOfRange(maxOf(0, bytes.size - PDF_WINDOW), bytes.size)
        val text = (head + tail).joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
        fun field(name: String): String? {
            Regex("/$name\\s*\\(((?:[^()\\\\]|\\\\.)*)\\)").find(text)?.groupValues?.get(1)?.let { return unescapePdf(it) }
            Regex("/$name\\s*<([0-9A-Fa-f]+)>").find(text)?.groupValues?.get(1)?.let { return utf16Hex(it) }
            return null
        }
        val title = field("Title")?.trim().orEmpty()
        val author = field("Author")?.trim().orEmpty()
        val year = field("CreationDate")?.let { YEAR.find(it)?.value?.toIntOrNull() }
        if (title.isEmpty() && author.isEmpty() && year == null) return null
        return Meta(title = title, author = author, year = year)
    }

    private fun unescapePdf(s: String): String {
        val raw = s.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
        // Строка в UTF-16BE с BOM «þÿ»
        if (raw.startsWith("þÿ")) {
            return raw.drop(2).chunked(2).filter { it.length == 2 }.map { ((it[0].code shl 8) or it[1].code).toChar() }.joinToString("")
        }
        return raw.filter { it.code >= 0x20 }
    }

    private fun utf16Hex(hex: String): String {
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
        val start = if (bytes.size >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF) 2 else 0
        return (start until bytes.size - 1 step 2).map { ((bytes[it] shl 8) or bytes[it + 1]).toChar() }.joinToString("")
    }

    private fun splitGenres(raw: String): List<String> =
        raw.split(',', ';', '/', '|').map { Genres.fromCode(it) }.filter { it.isNotEmpty() && it.length <= 40 }

    private const val FB2_HEAD = 96 * 1024
    private const val PDF_WINDOW = 256 * 1024
}

/** Жанры: коды FB2 → русские названия; прочие жанры приводятся к виду «С заглавной». */
object Genres {
    private val FB2 = mapOf(
        "sf" to "Научная фантастика", "sf_fantasy" to "Фэнтези", "fantasy" to "Фэнтези", "sf_space" to "Космическая фантастика",
        "sf_action" to "Боевая фантастика", "sf_heroic" to "Героическая фантастика", "sf_detective" to "Детективная фантастика",
        "sf_horror" to "Фантастика ужасов", "sf_humor" to "Юмористическая фантастика", "sf_cyberpunk" to "Киберпанк",
        "sf_history" to "Альтернативная история", "sf_social" to "Социальная фантастика", "sf_postapocalyptic" to "Постапокалипсис",
        "sf_etc" to "Фантастика", "detective" to "Детективы", "det_classic" to "Классический детектив",
        "det_police" to "Полицейский детектив", "det_action" to "Боевик", "det_irony" to "Иронический детектив",
        "det_history" to "Исторический детектив", "det_espionage" to "Шпионский детектив", "thriller" to "Триллер",
        "prose_contemporary" to "Современная проза", "prose_classic" to "Классическая проза", "prose_history" to "Историческая проза",
        "prose_military" to "Военная проза", "prose_rus_classic" to "Русская классика", "prose" to "Проза",
        "love_contemporary" to "Современный любовный роман", "love_history" to "Исторический любовный роман",
        "love_detective" to "Остросюжетный любовный роман", "love_short" to "Короткие любовные романы", "love_erotica" to "Эротика",
        "romance" to "Любовный роман", "adv_history" to "Исторические приключения", "adv_geo" to "Путешествия и география",
        "adv_maritime" to "Морские приключения", "adventure" to "Приключения", "child_tale" to "Сказки",
        "child_prose" to "Детская проза", "child_verse" to "Детские стихи", "child_sf" to "Детская фантастика",
        "children" to "Детская литература", "poetry" to "Поэзия", "dramaturgy" to "Драматургия", "humor" to "Юмор",
        "humor_prose" to "Юмористическая проза", "nonfiction" to "Документальная литература", "sci_history" to "История",
        "sci_psychology" to "Психология", "sci_philosophy" to "Философия", "sci_popular" to "Научно-популярная литература",
        "sci_tech" to "Технические науки", "sci_biology" to "Биология", "sci_math" to "Математика", "sci_phys" to "Физика",
        "sci_chem" to "Химия", "computers" to "Компьютеры", "comp_programming" to "Программирование", "comp_db" to "Базы данных",
        "comp_www" to "Интернет", "religion" to "Религия", "religion_esoterics" to "Эзотерика", "home" to "Дом и семья",
        "home_cooking" to "Кулинария", "ref_dict" to "Словари", "ref_encyc" to "Энциклопедии", "antique" to "Старинная литература",
        "antique_ant" to "Античная литература", "fanfiction" to "Фанфик", "network_literature" to "Сетевая литература",
        "litrpg" to "ЛитРПГ", "popadanec" to "Попаданцы", "business" to "Бизнес", "banking" to "Финансы",
        "military_special" to "Военное дело", "sport" to "Спорт"
    )

    /** Код жанра FB2 или свободный текст → название для полки. */
    fun fromCode(code: String): String {
        val key = code.trim().lowercase()
        if (key.isEmpty()) return ""
        FB2[key]?.let { return it }
        return key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
