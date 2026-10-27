package com.localdoc.ai.opds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор OPDS (Atom/XML) на синтетических каталогах в духе Project Gutenberg и Calibre. */
class OpdsTest {

    private val gutenbergRoot = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
          <id>root</id>
          <title>Каталог примера</title>
          <link rel="self" href="/ebooks.opds/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          <link rel="search" href="/ebooks/search.opds/?query={searchTerms}" type="application/atom+xml"/>
          <entry>
            <title>Популярные</title>
            <id>popular</id>
            <content type="text">Самые скачиваемые за неделю</content>
            <link rel="subsection" href="/ebooks/search.opds/?sort_order=downloads" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
          </entry>
          <entry>
            <title>Авторы</title>
            <id>authors</id>
            <link rel="http://opds-spec.org/sort/popular" href="authors.opds" type="application/atom+xml;profile=opds-catalog"/>
          </entry>
        </feed>"""

    private val acquisitionFeed = """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog">
          <title>Результаты</title>
          <link rel="next" href="page2.opds" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="start" href="/root.opds" type="application/atom+xml;profile=opds-catalog"/>
          <entry>
            <title>Война и мир</title>
            <id>urn:book:1</id>
            <author><name>Толстой, Лев</name></author>
            <author><name>Редактор Второй</name></author>
            <summary>Роман-эпопея.</summary>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Описание <b>книги</b>.</p></div></content>
            <link rel="http://opds-spec.org/image/thumbnail" href="covers/1_small.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition/open-access" href="/get/1.epub" type="application/epub+zip" title="EPUB"/>
            <link rel="http://opds-spec.org/acquisition/open-access" href="/get/1.fb2.zip" type="application/x-zip-compressed-fb2"/>
            <link rel="http://opds-spec.org/acquisition" href="/get/1.mobi" type="application/x-mobipocket-ebook"/>
            <link rel="http://opds-spec.org/acquisition/buy" href="/buy/1" type="text/html"/>
            <link rel="http://opds-spec.org/acquisition/sample" href="/sample/1.epub" type="application/epub+zip"/>
          </entry>
          <entry>
            <title>Только текст</title>
            <id>urn:book:2</id>
            <link rel="http://opds-spec.org/acquisition" href="https://files.example/2.txt" type="text/plain; charset=utf-8"/>
          </entry>
          <entry>
            <title>Только MOBI</title>
            <id>urn:book:3</id>
            <link rel="http://opds-spec.org/acquisition" href="/get/3.mobi" type="application/x-mobipocket-ebook"/>
          </entry>
        </feed>"""

    @Test
    fun `navigation feed gives sections with absolute addresses and a search link`() {
        val feed = OpdsParser.parseFeed(gutenbergRoot, "https://catalog.example/ebooks.opds/")
        assertEquals("Каталог примера", feed.title)
        assertEquals(listOf("Популярные", "Авторы"), feed.entries.map { it.title })
        assertEquals("https://catalog.example/ebooks/search.opds/?sort_order=downloads", feed.entries[0].navigationUrl)
        assertEquals("https://catalog.example/ebooks.opds/authors.opds", feed.entries[1].navigationUrl)
        assertEquals("Самые скачиваемые за неделю", feed.entries[0].summary)
        assertTrue(feed.entries.none { it.isBook })
        assertEquals("https://catalog.example/ebooks/search.opds/?query={searchTerms}", feed.search?.href)
        assertFalse(feed.search?.isDescription ?: true)
    }

    @Test
    fun `acquisition feed gives books authors cover and next page`() {
        val feed = OpdsParser.parseFeed(acquisitionFeed, "https://catalog.example/search.opds/q")
        assertEquals("https://catalog.example/search.opds/page2.opds", feed.nextUrl)
        assertEquals("https://catalog.example/root.opds", feed.startUrl)
        val book = feed.entries.first()
        assertTrue(book.isBook)
        assertEquals(listOf("Толстой, Лев", "Редактор Второй"), book.authors)
        assertEquals("https://catalog.example/search.opds/covers/1_small.jpg", book.thumbnail)
        assertEquals("Роман-эпопея.", book.summary)
    }

    @Test
    fun `only free formats the app can open are offered`() {
        val feed = OpdsParser.parseFeed(acquisitionFeed, "https://catalog.example/search.opds/q")
        val first = feed.entries[0].downloads
        assertEquals(listOf("EPUB", "FB2"), first.map { it.label })
        assertTrue(first.none { it.url.contains("buy") || it.url.contains("sample") || it.url.endsWith(".mobi") })
        assertEquals("TXT", feed.entries[1].downloads.single().label)
        assertTrue(feed.entries[2].downloads.isEmpty())
        assertFalse(feed.entries[2].isBook, "книга только в неоткрываемом формате не считается скачиваемой")
    }

    @Test
    fun `best download prefers epub then fb2 then text`() {
        val feed = OpdsParser.parseFeed(acquisitionFeed, "https://catalog.example/x")
        assertEquals("EPUB", feed.entries[0].bestDownload()?.label)
        assertEquals("TXT", feed.entries[1].bestDownload()?.label)
        assertNull(feed.entries[2].bestDownload())
    }

    @Test
    fun `indirect acquisition type is used when the link itself is a page`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog"><title>t</title>
            <entry><title>b</title><id>1</id>
              <link rel="http://opds-spec.org/acquisition" href="/dl/1" type="text/html">
                <opds:indirectAcquisition type="application/epub+zip"/>
              </link>
            </entry></feed>"""
        val entry = OpdsParser.parseFeed(xml, "https://catalog.example/").entries.single()
        assertEquals("EPUB", entry.bestDownload()?.label)
        assertEquals("https://catalog.example/dl/1", entry.bestDownload()?.url)
    }

    @Test
    fun `file names of downloads follow the title and format`() {
        val feed = OpdsParser.parseFeed(acquisitionFeed, "https://catalog.example/x")
        assertEquals("Война и мир.epub", feed.entries[0].bestDownload()?.let { OpdsFormats.fileName(feed.entries[0].title, it) })
        assertEquals("a b.txt", OpdsFormats.fileName("a/b", OpdsDownload("https://x/1", "text/plain", "TXT")))
    }

    @Test
    fun `search template is filled with an encoded query and optional parameters are dropped`() {
        assertEquals(
            "https://c.example/search?query=%D0%B2%D0%BE%D0%B9%D0%BD%D0%B0%20%26%20%D0%BC%D0%B8%D1%80",
            OpdsParser.searchUrl("https://c.example/search?query={searchTerms}&startIndex={startIndex?}&count={count?}", "война & мир")
        )
        assertEquals("https://c.example/s/abc", OpdsParser.searchUrl("https://c.example/s/{searchTerms}", "abc"))
    }

    @Test
    fun `opensearch description gives the atom template`() {
        val xml = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <ShortName>Calibre</ShortName>
            <Url type="text/html" template="/html?q={searchTerms}"/>
            <Url type="application/atom+xml" template="/opds/search/{searchTerms}"/>
          </OpenSearchDescription>"""
        assertEquals("https://calibre.local:8080/opds/search/{searchTerms}", OpdsParser.openSearchTemplate(xml, "https://calibre.local:8080/opds/opensearch.xml"))
    }

    @Test
    fun `search link to a description document is marked`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Calibre</title>
            <link rel="search" href="/opds/opensearch.xml" type="application/opensearchdescription+xml"/></feed>"""
        val feed = OpdsParser.parseFeed(xml, "https://calibre.local:8080/opds")
        assertTrue(feed.search?.isDescription == true)
        assertEquals("https://calibre.local:8080/opds/opensearch.xml", feed.search?.href)
    }

    @Test
    fun `calibre style feed with empty fields parses`() {
        val xml = """<?xml version='1.0' encoding='utf-8'?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>calibre</title>
              <entry><title>Книга без автора</title><id>urn:uuid:1</id><updated>2026-01-01T00:00:00+00:00</updated>
                <link type="application/epub+zip" href="/get/epub/5/Lib" rel="http://opds-spec.org/acquisition"/>
                <link type="image/jpeg" href="/get/thumb/5/Lib" rel="http://opds-spec.org/image/thumbnail"/>
              </entry></feed>"""
        val entry = OpdsParser.parseFeed(xml, "http://192.168.1.5:8080/opds").entries.single()
        assertTrue(entry.authors.isEmpty())
        assertEquals("http://192.168.1.5:8080/get/epub/5/Lib", entry.bestDownload()?.url)
        assertEquals("", entry.summary)
    }

    @Test
    fun `dangerous links are dropped`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title>
            <entry><title>b</title><id>1</id>
              <link rel="http://opds-spec.org/acquisition" href="javascript:alert(1)" type="application/epub+zip"/>
              <link rel="http://opds-spec.org/acquisition" href="file:///etc/passwd" type="application/epub+zip"/>
              <link rel="http://opds-spec.org/acquisition" href="ftp://x/1.epub" type="application/epub+zip"/>
            </entry></feed>"""
        assertTrue(OpdsParser.parseFeed(xml, "https://c.example/").entries.single().downloads.isEmpty())
    }

    @Test
    fun `not an opds document is rejected with a clear message`() {
        val error = assertFailsWith<IllegalArgumentException> { OpdsParser.parseFeed("<html><body>Hi</body></html>", "https://x.example/") }
        assertNotNull(error.message)
        assertFailsWith<IllegalArgumentException> { OpdsParser.parseFeed("", "https://x.example/") }
    }

    @Test
    fun `catalog address validation accepts http and https only`() {
        assertEquals("https://c.example/opds", OpdsCatalogs.normalizeAddress("  https://c.example/opds "))
        assertEquals("https://c.example/opds", OpdsCatalogs.normalizeAddress("c.example/opds"))
        assertNull(OpdsCatalogs.normalizeAddress("ftp://c.example"))
        assertNull(OpdsCatalogs.normalizeAddress("file:///x"))
        assertNull(OpdsCatalogs.normalizeAddress(""))
    }

    @Test
    fun `catalog list keeps presets, adds custom ones without duplicates and survives a restart`() {
        var saved: String? = null
        val store = OpdsCatalogStore({ saved }, { saved = it })
        assertTrue(store.all.value.any { it.url.contains("gutenberg") })
        store.add("Мой Calibre", "http://192.168.1.5:8080/opds")
        store.add("Мой Calibre ещё раз", "http://192.168.1.5:8080/opds/")
        assertEquals(1, store.all.value.count { it.url.contains("192.168.1.5") })
        val again = OpdsCatalogStore({ saved }, { saved = it })
        assertTrue(again.all.value.any { it.title == "Мой Calibre" })
        again.remove(again.all.value.first { it.title == "Мой Calibre" }.id)
        assertTrue(again.all.value.none { it.url.contains("192.168.1.5") })
    }
}
