package com.localdoc.ai.parser

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.network.parseGetRequest
import com.fleeksoft.ksoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Результат разбора документа. */
data class ParsedDoc(
    val title: String,
    val content: String,
    val source: String = "",
    val pageCount: Int = 1
) {
    val approxTokens: Int get() = content.length / 3 // грубая оценка для русского текста
}

/**
 * Извлечение чистого текста статьи из веб-страницы (аналог Reader Mode).
 * Работает на 100% в общем коде через Ksoup.
 */
class WebArticleParser {

    suspend fun parseUrl(url: String): ParsedDoc = withContext(Dispatchers.IO) {
        val normalizedUrl = if (url.startsWith("http")) url else "https://$url"
        val doc: Document = Ksoup.parseGetRequest(url = normalizedUrl)
        extractArticle(doc, normalizedUrl)
    }

    fun extractArticle(doc: Document, source: String): ParsedDoc {
        // Удаляем мусорные узлы перед извлечением текста статьи
        doc.select("script, style, nav, footer, header, aside, noscript, form, iframe, svg").remove()

        val mainContent = doc
            .select("article, main, [role=main]")
            .text()
            .ifBlank { doc.body().text() }

        return ParsedDoc(
            title = doc.title().ifBlank { source },
            content = mainContent,
            source = source
        )
    }
}
