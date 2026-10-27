package com.localdoc.ai.sync

import com.localdoc.ai.net.HttpTransport
import com.localdoc.ai.platform.RawRequest
import com.localdoc.ai.platform.RawResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavTransportTest {

    private class Fake(val respond: (RawRequest) -> RawResponse) : HttpTransport {
        val requests = ArrayList<RawRequest>()
        override val userAgent = "test"
        override suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse {
            requests += request
            return respond(request)
        }
    }

    private fun response(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) =
        RawResponse(status, headers, emptyList(), body)

    private fun transport(http: HttpTransport) = WebDavTransport(http, "https://cloud.example/dav/localdoc/", "user", "pass")

    @Test
    fun `download returns bytes and etag and null when the file does not exist`() = runTest {
        val body = byteArrayOf(1, 2, 3)
        val ok = Fake { response(200, body, mapOf("etag" to "\"abc\"")) }
        val file = transport(ok).download()
        assertContentEquals(body, file?.bytes)
        assertEquals("\"abc\"", file?.version)
        assertEquals("https://cloud.example/dav/localdoc/localdoc-sync.ldsync", ok.requests.single().url)
        assertTrue(ok.requests.single().headers.getValue("Authorization").startsWith("Basic "))

        assertNull(transport(Fake { response(404) }).download())
    }

    @Test
    fun `upload sets the right precondition header`() = runTest {
        val http = Fake { response(201, headers = mapOf("etag" to "\"new\"")) }
        val t = transport(http)
        assertEquals("\"new\"", t.upload(byteArrayOf(1), null))
        t.upload(byteArrayOf(1), "\"old\"")
        t.upload(byteArrayOf(1), "")
        assertEquals("*", http.requests[0].headers["If-None-Match"])
        assertEquals("\"old\"", http.requests[1].headers["If-Match"])
        assertTrue(http.requests[2].headers.keys.none { it.startsWith("If-") })
        assertTrue(http.requests.all { it.method == "PUT" })
    }

    @Test
    fun `a lost precondition is a conflict and server errors have readable messages`() = runTest {
        assertFailsWith<UploadConflictException> { transport(Fake { response(412) }).upload(byteArrayOf(1), "\"x\"") }
        val denied = assertFailsWith<SyncTransportException> { transport(Fake { response(401) }).download() }
        assertTrue("логин" in denied.message.orEmpty())
        val full = assertFailsWith<SyncTransportException> { transport(Fake { response(507) }).upload(byteArrayOf(1), null) }
        assertEquals(507, full.status)
    }

    @Test
    fun `a dropped connection becomes a transport error`() = runTest {
        val broken = Fake { throw IllegalStateException("reset") }
        assertFailsWith<SyncTransportException> { transport(broken).download() }
    }
}
