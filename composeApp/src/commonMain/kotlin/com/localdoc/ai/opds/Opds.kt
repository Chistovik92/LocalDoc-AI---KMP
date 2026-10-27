package com.localdoc.ai.opds

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import com.localdoc.ai.book.WebNavigationDetector
import com.localdoc.ai.domain.DownloadedFile
import com.localdoc.ai.domain.NovelDownloader
import com.localdoc.ai.net.HttpStatusException
import com.localdoc.ai.net.WebHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Вариант скачивания книги: адрес, тип содержимого и подпись формата («EPUB»). */
data class OpdsDownload(val url: String, val mime: String, val label: String)

/** Поиск каталога: либо готовый шаблон с `{searchTerms}`, либо адрес описания OpenSearch, в котором шаблон нужно найти. */
data class OpdsSearch(val href: String, val isDescription: Boolean)

/** Запись каталога: раздел (переход по [navigationUrl]) или книга (варианты в [downloads]). */
class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    val summary: String,
    val thumbnail: String?,
    val navigationUrl: String?,
    val downloads: List<OpdsDownload>
) {
    val isBook: Boolean get() = downloads.isNotEmpty()

    /** Предпочтительный формат: EPUB, затем FB2, затем остальные. */
    fun bestDownload(): OpdsDownload? = downloads.firstOrNull()
}

class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String?,
    val startUrl: String?,
    val search: OpdsSearch?
)

/** Форматы, которые приложение открывает без платформенных плагинов, в порядке предпочтения. */
object OpdsFormats {
    private val ORDER = listOf("EPUB", "FB2", "DOCX", "ODT", "RTF", "MD", "TXT")

    /** Подпись формата по типу содержимого и адресу; null — формат не поддерживается (MOBI, PDF, архивы комиксов…). */
    fun label(mime: String, url: String): String? {
        val type = mime.substringBefore(';').trim().lowercase()
        val path = url.substringBefore('?').lowercase()
        return when {
            type == "application/epub+zip" || (type.isEmpty() && path.endsWith(".epub")) -> "EPUB"
            "fictionbook" in type || "fb2" in type || path.endsWith(".fb2") || path.endsWith(".fb2.zip") -> "FB2"
            type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
            type == "application/vnd.oasis.opendocument.text" -> "ODT"
            type == "application/rtf" || type == "text/rtf" -> "RTF"
            type == "text/markdown" -> "MD"
            type == "text/plain" -> "TXT"
            type.isEmpty() || type == "application/octet-stream" -> when {
                path.endsWith(".txt") -> "TXT"
                path.endsWith(".docx") -> "DOCX"
                path.endsWith(".odt") -> "ODT"
                path.endsWith(".rtf") -> "RTF"
                else -> null
            }
            else -> null
        }
    }

    fun rank(label: String): Int = ORDER.indexOf(label).let { if (it < 0) ORDER.size else it }

    /** Имя файла для скачанной книги: название без запрещённых символов и расширение по формату. */
    fun fileName(title: String, download: OpdsDownload): String {
        val clean = title.map { if (it in "/\\:*?\"<>|") ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(100)
            .ifBlank { "book" }
        val extension = when (download.label) {
            "FB2" -> if (download.url.substringBefore('?').lowercase().endsWith(".zip")) "fb2.zip" else "fb2"
            else -> download.label.lowercase()
        }
        return "$clean.$extension"
    }
}

object OpdsParser {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
    private val OPTIONAL_PARAM = Regex("\\{[^}]*\\?\\}")
    private val ANY_PARAM = Regex("\\{[^}]*\\}")
    private val NOT_NAVIGATION = setOf("self", "alternate", "next", "previous", "prev", "first", "last", "start", "up", "search", "related-feed")
    private const val ACQUISITION = "http://opds-spec.org/acquisition"
    private val PAID = setOf("buy", "sample", "borrow", "subscribe")

    /** Разбирает ленту OPDS; [baseUrl] — адрес, с которого она получена (относительные ссылки превращаются в абсолютные). */
    fun parseFeed(xml: String, baseUrl: String): OpdsFeed {
        val root = try {
            Ksoup.parse(xml, Parser.xmlParser(), "").children().firstOrNull()
        } catch (e: Exception) {
            null
        }
        require(root != null && root.local() == "feed") { "Это не каталог OPDS: по адресу нет ленты Atom" }

        val links = root.children().filter { it.local() == "link" }
        val entries = root.children().filter { it.local() == "entry" }.map { parseEntry(it, baseUrl) }
        val search = links.firstOrNull { it.attr("rel") == "search" }?.let { link ->
            resolve(baseUrl, link.attr("href"))?.let { OpdsSearch(it, link.attr("type").contains("opensearchdescription", true)) }
        }
        return OpdsFeed(
            title = root.childText("title"),
            entries = entries,
            nextUrl = links.firstOrNull { it.attr("rel") == "next" }?.let { resolve(baseUrl, it.attr("href")) },
            startUrl = links.firstOrNull { it.attr("rel") == "start" }?.let { resolve(baseUrl, it.attr("href")) },
            search = search
        )
    }

    private fun parseEntry(el: Element, baseUrl: String): OpdsEntry {
        val links = el.children().filter { it.local() == "link" }
        val downloads = links.mapNotNull { acquisition(it, baseUrl) }
            .distinctBy { it.url }
            .sortedBy { OpdsFormats.rank(it.label) }
        val thumbnail = links.firstOrNull { it.attr("rel").endsWith("/image/thumbnail") || it.attr("rel") == "x-stanza-cover-image-thumbnail" }
            ?: links.firstOrNull { it.attr("rel").endsWith("/image") || it.attr("rel") == "x-stanza-cover-image" }
        val navigation = if (downloads.isNotEmpty()) null else links.firstOrNull { link ->
            link.attr("type").contains("atom+xml", true) && link.attr("rel") !in NOT_NAVIGATION && !link.attr("rel").startsWith(ACQUISITION)
        }
        val authors = el.children().filter { it.local() == "author" }.mapNotNull { author ->
            author.children().firstOrNull { it.local() == "name" }?.text()?.trim()?.takeIf { it.isNotEmpty() }
        }
        val summary = el.childText("summary").ifEmpty { el.childText("content") }
        return OpdsEntry(
            id = el.childText("id"),
            title = el.childText("title").ifEmpty { "Без названия" },
            authors = authors,
            summary = summary,
            thumbnail = thumbnail?.let { resolve(baseUrl, it.attr("href")) },
            navigationUrl = navigation?.let { resolve(baseUrl, it.attr("href")) },
            downloads = downloads
        )
    }

    /** Бесплатная ссылка на файл в поддерживаемом формате; платные, пробные и «взять на время» ссылки пропускаются. */
    private fun acquisition(link: Element, baseUrl: String): OpdsDownload? {
        val rel = link.attr("rel")
        if (!rel.startsWith(ACQUISITION) || rel.substringAfterLast('/') in PAID) return null
        val url = resolve(baseUrl, link.attr("href")) ?: return null
        val indirect = link.children().firstOrNull { it.local() == "indirectacquisition" }?.attr("type").orEmpty()
        val mime = indirect.ifEmpty { link.attr("type") }
        val label = OpdsFormats.label(mime, url) ?: return null
        return OpdsDownload(url, mime, label)
    }

    /** Шаблон поиска из документа OpenSearch (с типом Atom); null — подходящего адреса нет. */
    fun openSearchTemplate(xml: String, baseUrl: String): String? {
        val root = try {
            Ksoup.parse(xml, Parser.xmlParser(), "").children().firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null
        val chosen = root.children().firstOrNull {
            it.local() == "url" && it.attr("template").isNotBlank() && it.attr("type").contains("atom+xml", true)
        } ?: return null
        return resolve(baseUrl, chosen.attr("template"))
    }

    /** Подставляет [query] в шаблон: пробелы и спецсимволы кодируются, необязательные параметры `{x?}` убираются. */
    fun searchUrl(template: String, query: String): String {
        val encoded = percentEncode(query.trim())
        fun fill(s: String) = s.replace("{searchTerms}", encoded).replace(OPTIONAL_PARAM, "").replace(ANY_PARAM, "")
        val q = template.indexOf('?')
        if (q < 0) return fill(template)
        val params = template.substring(q + 1).split('&').filterNot { OPTIONAL_PARAM.matches(it.substringAfter('=', "")) }
        return fill(template.substring(0, q)) + "?" + params.joinToString("&") { fill(it) }
    }

    private fun percentEncode(s: String): String {
        val out = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    /** Абсолютный адрес http(s); любые другие схемы (`javascript:`, `file:`, `ftp:`) и пустые ссылки отбрасываются. */
    private fun resolve(base: String, href: String): String? {
        val h = href.trim()
        val scheme = SCHEME.find(h)?.value
        if (scheme != null && !scheme.startsWith("http:", true) && !scheme.startsWith("https:", true)) return null
        return WebNavigationDetector.resolveUrl(base, h)
    }

    private fun Element.local(): String = normalName().substringAfter(':')

    private fun Element.childText(name: String): String =
        children().firstOrNull { it.local() == name }?.text()?.trim().orEmpty()
}

/** Известные каталоги: предустановленные и добавленные пользователем. */
@Serializable
data class OpdsCatalog(val id: String, val title: String, val url: String, val builtin: Boolean = false)

object OpdsCatalogs {
    /** Только каталоги с общественным достоянием; свои серверы (Calibre и т. п.) пользователь добавляет сам. */
    val PRESETS = listOf(
        OpdsCatalog("gutenberg", "Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/", builtin = true)
    )

    /** Адрес каталога, введённый пользователем: без схемы — https; кроме http(s) ничего не принимается. */
    fun normalizeAddress(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = when {
            "://" in text -> text
            text.startsWith("//") -> "https:$text"
            else -> "https://$text"
        }
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        return withScheme.takeIf { it.substringAfter("://").isNotBlank() }
    }
}

/** Список каталогов в KV (JSON); хранятся только добавленные пользователем, предустановленные подмешиваются. */
class OpdsCatalogStore(private val load: () -> String?, private val save: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(OpdsCatalog.serializer())
    private val custom = MutableStateFlow(read())
    private val _all = MutableStateFlow(OpdsCatalogs.PRESETS + custom.value)
    val all: StateFlow<List<OpdsCatalog>> = _all.asStateFlow()

    /** Добавляет каталог; тот же адрес (без учёта `/` в конце и регистра) второй раз не добавляется. Возвращает false при неверном адресе. */
    fun add(title: String, rawUrl: String): Boolean {
        val url = OpdsCatalogs.normalizeAddress(rawUrl) ?: return false
        val key = url.trimEnd('/').lowercase()
        if (_all.value.any { it.url.trimEnd('/').lowercase() == key }) return true
        val name = title.trim().ifEmpty { url.substringAfter("://").substringBefore('/') }
        commit(custom.value + OpdsCatalog("c" + NovelDownloader.folderId(url), name, url))
        return true
    }

    fun remove(id: String) {
        if (custom.value.none { it.id == id }) return
        commit(custom.value.filterNot { it.id == id })
    }

    private fun commit(next: List<OpdsCatalog>) {
        custom.value = next
        _all.value = OpdsCatalogs.PRESETS + next
        save(json.encodeToString(serializer, next))
    }

    private fun read(): List<OpdsCatalog> =
        load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty().filterNot { it.builtin }
}

/** Сетевая часть: ленты и файлы идут через общий клиент с очередью запросов к сайту. */
class OpdsClient(private val http: WebHttp) {

    suspend fun load(url: String): OpdsFeed {
        val result = http.get(url, headers = mapOf("Accept" to ACCEPT), maxBytes = MAX_FEED_BYTES)
        if (!result.isOk) throw HttpStatusException(result.status, result.url)
        return withContext(Dispatchers.Default) { OpdsParser.parseFeed(result.text(), result.url) }
    }

    /** Шаблон поиска каталога; если он лежит в описании OpenSearch, описание загружается один раз. */
    suspend fun searchTemplate(feed: OpdsFeed): String? {
        val search = feed.search ?: return null
        if (!search.isDescription) return search.href
        val result = http.get(search.href, maxBytes = MAX_FEED_BYTES)
        if (!result.isOk) throw HttpStatusException(result.status, result.url)
        return withContext(Dispatchers.Default) { OpdsParser.openSearchTemplate(result.text(), result.url) }
    }

    suspend fun search(feed: OpdsFeed, query: String): OpdsFeed {
        val template = searchTemplate(feed) ?: throw IllegalStateException("У этого каталога нет поиска")
        return load(OpdsParser.searchUrl(template, query))
    }

    suspend fun download(download: OpdsDownload, title: String): DownloadedFile {
        val result = http.get(download.url, maxBytes = WebHttp.MAX_DOWNLOAD_BYTES)
        if (!result.isOk) throw HttpStatusException(result.status, result.url)
        if (result.contentType.startsWith("text/html")) throw IllegalStateException("Сервер вместо книги прислал страницу — возможно, нужен вход")
        return DownloadedFile(OpdsFormats.fileName(title, download), result.body)
    }

    private companion object {
        const val ACCEPT = "application/atom+xml,application/xml;q=0.9,text/xml;q=0.8,*/*;q=0.5"
        const val MAX_FEED_BYTES = 6L * 1024 * 1024
    }
}
