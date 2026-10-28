package com.localdoc.ai.net

import com.localdoc.ai.platform.RawRequest
import com.localdoc.ai.platform.RawResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Аудит безопасности 2.0.0: общий HTTP-клиент не ведёт на внутренние адреса и не раздаёт заголовки авторизации. */
class WebHttpSecurityTest {

    private fun client(handler: (RawRequest) -> RawResponse): Pair<FakeTransport, WebHttp> {
        val transport = FakeTransport(handler)
        return transport to WebHttp(transport, CookieJar(MemoryStorage()) { 1_000_000_000_000L })
    }

    @Test
    fun `a site cannot bounce the app to the cloud metadata address`() = runTest {
        val (transport, http) = client { req ->
            if (req.url.startsWith("https://novel.example")) redirect("http://169.254.169.254/latest/meta-data/") else page("секрет")
        }
        assertFailsWith<UnsafeRedirectException> { http.get("https://novel.example/chapter/1") }
        assertEquals(1, transport.requests.size, "второй запрос не должен уходить")
    }

    @Test
    fun `a site cannot bounce the app to localhost or the home network`() = runTest {
        for (target in listOf("http://localhost:3000/", "http://192.168.0.1/admin", "https://10.0.0.2/")) {
            val (transport, http) = client { req -> if (req.url.contains("novel.example")) redirect(target) else page("внутри") }
            assertFailsWith<UnsafeRedirectException>(target) { http.get("https://novel.example/x") }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `https is not downgraded to http by a redirect`() = runTest {
        val (_, http) = client { req -> if (req.url.startsWith("https://")) redirect("http://novel.example/x") else page("открыто") }
        assertFailsWith<UnsafeRedirectException> { http.get("https://novel.example/x") }
    }

    @Test
    fun `a catalog in the home network may redirect inside it`() = runTest {
        val (_, http) = client { req ->
            if (req.url.endsWith("/opds")) redirect("http://192.168.1.5:8080/opds/") else page("лента")
        }
        assertEquals("лента", http.get("http://192.168.1.5:8080/opds").text())
    }

    @Test
    fun `authorization header is not forwarded to another host after a redirect`() = runTest {
        val (transport, http) = client { req ->
            if (req.url.startsWith("https://books.example")) redirect("https://cdn.other.example/file") else page("ok")
        }
        http.get("https://books.example/file", headers = mapOf("Authorization" to "Basic c2VjcmV0", "Accept" to "x/y"))
        val first = transport.requests[0]
        val second = transport.requests[1]
        assertEquals("Basic c2VjcmV0", first.headers["Authorization"])
        assertNull(second.headers["Authorization"], "пароль не должен уходить на чужой хост")
        assertEquals("x/y", second.headers["Accept"])
    }

    @Test
    fun `authorization header survives a redirect inside the same host`() = runTest {
        val (transport, http) = client { req -> if (req.url.endsWith("/a")) redirect("/b") else page("ok") }
        http.get("https://books.example/a", headers = mapOf("Authorization" to "Basic c2VjcmV0"))
        assertEquals("Basic c2VjcmV0", transport.requests[1].headers["Authorization"])
        assertTrue(transport.requests[1].url.endsWith("/b"))
    }
}
