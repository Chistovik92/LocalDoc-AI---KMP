package com.localdoc.ai.lang

import com.localdoc.ai.net.CookieJar
import com.localdoc.ai.net.HttpTransport
import com.localdoc.ai.net.MemoryStorage
import com.localdoc.ai.net.WebHttp
import com.localdoc.ai.platform.RawRequest
import com.localdoc.ai.platform.RawResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Разбор ответа и запросы открытого эндпоинта Google Translate (`translate_a/single`). */
class GoogleWebTranslatorTest {

    private val ru = Language("ru", "Русский", "Русский", "Russian", "ru-RU")

    private fun translator(respond: (RawRequest) -> RawResponse): Pair<FakeTranslatorTransport, GoogleWebTranslator> {
        val transport = FakeTranslatorTransport(respond)
        val http = WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L })
        return transport to GoogleWebTranslator(http)
    }

    private fun ok(body: String) = RawResponse(200, mapOf("content-type" to "application/json"), emptyList(), body.encodeToByteArray())

    @Test
    fun `translation segments are joined into one text`() {
        val response = """[[["Привет","Hello",null,null,10],[" мир"," world",null,null,5]],null,"en",null,null,null,null,[]]"""
        assertEquals("Привет мир", translator { ok(response) }.second.parse(response))
    }

    @Test
    fun `single segment response`() {
        val response = """[[["Перевод","Text",null,null,1]],null,"en"]"""
        assertEquals("Перевод", translator { ok(response) }.second.parse(response))
    }

    @Test
    fun `empty segments give empty translation`() {
        assertEquals("", translator { ok("") }.second.parse("""[[],null,"en"]"""))
    }

    @Test
    fun `malformed response is an error, not a crash of the reader`() {
        val parse = translator { ok("") }.second::parse
        assertFailsWith<Exception> { parse("not json at all") }
        assertFailsWith<Exception> { parse("""{"error":"quota"}""") }
        assertFailsWith<Exception> { parse("""[null,null,"en"]""") }
    }

    @Test
    fun `request carries encoded text, target and auto source language`() = runTest {
        val (transport, google) = translator { ok("""[[["Привет мир","Hello world",null,null,1]],null,"en"]""") }
        val result = google.translate("Hello world", ru, null).toList().joinToString("")
        assertEquals("Привет мир", result)
        val url = transport.requests.single()
        assertTrue(url.startsWith("https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=auto&tl=ru&q="), url)
        assertTrue(url.endsWith("Hello%20world"), url)
    }

    @Test
    fun `source language goes into sl parameter`() = runTest {
        val (transport, google) = translator { ok("""[[["Привет","Hello",null,null,1]],null,"en"]""") }
        google.translate("Hello", ru, Language("en", "English", "English", "English", "en-US")).toList()
        assertTrue(transport.requests.single().contains("sl=en"), transport.requests.single())
    }

    @Test
    fun `blank text is returned without a request`() = runTest {
        val (transport, google) = translator { error("не должно быть запроса") }
        assertEquals("  ", google.translate("  ", ru, null).toList().joinToString(""))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `service error surfaces as a translation failure`() = runTest {
        val (_, google) = translator { RawResponse(429, emptyMap(), emptyList(), "too many".encodeToByteArray()) }
        assertFailsWith<Exception> { google.translate("Hello", ru, null).toList() }
    }
}

/** Транспорт-заглушка: помнит запросы, отвечает заданным [respond]. */
class FakeTranslatorTransport(private val respond: (RawRequest) -> RawResponse) : HttpTransport {
    val requests = ArrayList<String>()
    override val userAgent: String = "test-agent"
    override suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse {
        requests.add(request.url)
        return respond(request)
    }
}
