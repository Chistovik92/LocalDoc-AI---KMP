package com.localdoc.ai.opds

import com.localdoc.ai.net.CookieJar
import com.localdoc.ai.net.FakeTransport
import com.localdoc.ai.net.HostThrottle
import com.localdoc.ai.net.HttpStatusException
import com.localdoc.ai.net.MemoryStorage
import com.localdoc.ai.net.WebHttp
import com.localdoc.ai.net.page
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Клиент OPDS поверх общего клиента: поиск через описание OpenSearch, скачивание, отказы сайта. */
class OpdsClientTest {

    private val root = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Корень</title>
        <link rel="search" href="/opds/opensearch.xml" type="application/opensearchdescription+xml"/></feed>"""
    private val description = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
        <Url type="application/atom+xml" template="/opds/search/{searchTerms}"/></OpenSearchDescription>"""
    private val results = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Найдено</title>
        <entry><title>Книга</title><id>1</id><link rel="http://opds-spec.org/acquisition" href="/get/1.epub" type="application/epub+zip"/></entry></feed>"""

    private fun client(handler: (String) -> com.localdoc.ai.platform.RawResponse): Pair<OpdsClient, FakeTransport> {
        val transport = FakeTransport { handler(it.url) }
        val http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L }, HostThrottle(intervalMs = 0, sleep = {}))
        return OpdsClient(http) to transport
    }

    @Test
    fun `search goes through the description document`() = runTest {
        val (client, transport) = client { url ->
            when {
                url.endsWith("/opensearch.xml") -> page(description)
                url.contains("/search/") -> page(results)
                else -> page(root)
            }
        }
        val feed = client.load("https://calibre.local/opds")
        val found = client.search(feed, "война и мир")
        assertEquals("Книга", found.entries.single().title)
        assertTrue(transport.requests.last().url.endsWith("/opds/search/%D0%B2%D0%BE%D0%B9%D0%BD%D0%B0%20%D0%B8%20%D0%BC%D0%B8%D1%80"))
    }

    @Test
    fun `the accept header asks for atom`() = runTest {
        val (client, transport) = client { page(root) }
        client.load("https://calibre.local/opds")
        assertTrue(transport.requests.single().headers.getValue("Accept").startsWith("application/atom+xml"))
    }

    @Test
    fun `site refusal surfaces as an http status error`() = runTest {
        val (client, _) = client { page("busy", status = 503) }
        val error = assertFailsWith<HttpStatusException> { client.load("https://calibre.local/opds") }
        assertEquals(503, error.status)
    }

    @Test
    fun `a html page instead of a book is refused`() = runTest {
        val (client, _) = client { page("<html>login</html>", headers = mapOf("content-type" to "text/html")) }
        assertFailsWith<IllegalStateException> { client.download(OpdsDownload("https://calibre.local/get/1", "application/epub+zip", "EPUB"), "Книга") }
    }

    @Test
    fun `a downloaded book gets a file name from its title`() = runTest {
        val (client, _) = client { page("bytes", headers = mapOf("content-type" to "application/epub+zip")) }
        val file = client.download(OpdsDownload("https://calibre.local/get/1", "application/epub+zip", "EPUB"), "Война и мир")
        assertEquals("Война и мир.epub", file.name)
    }
}
