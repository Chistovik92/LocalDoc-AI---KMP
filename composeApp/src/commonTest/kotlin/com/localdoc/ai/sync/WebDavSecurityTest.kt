package com.localdoc.ai.sync

import com.localdoc.ai.net.HttpTransport
import com.localdoc.ai.platform.RawRequest
import com.localdoc.ai.platform.RawResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Аудит безопасности 2.0.0: пароль WebDAV не уходит по открытому http в интернет. */
class WebDavSecurityTest {

    private class Fake : HttpTransport {
        val requests = ArrayList<RawRequest>()
        override val userAgent = "test"
        override suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse {
            requests += request
            return RawResponse(404, emptyMap(), emptyList(), ByteArray(0))
        }
    }

    @Test
    fun `password is never sent over plain http to a public server`() = runTest {
        val http = Fake()
        val transport = WebDavTransport(http, "http://cloud.example/dav", "user", "pass")
        val e = assertFailsWith<SyncTransportException> { transport.download() }
        assertTrue("https" in e.message.orEmpty(), e.message)
        assertFailsWith<SyncTransportException> { transport.upload(byteArrayOf(1), null) }
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `plain http is allowed for a server in the home network`() = runTest {
        for (url in listOf("http://192.168.1.20/dav", "http://nas.local:5005/dav", "http://localhost:8080/dav")) {
            val http = Fake()
            WebDavTransport(http, url, "user", "pass").download()
            assertEquals(1, http.requests.size, url)
        }
    }

    @Test
    fun `https works anywhere and non-http schemes are refused`() = runTest {
        val http = Fake()
        WebDavTransport(http, "https://cloud.example/dav", "user", "pass").download()
        assertEquals(1, http.requests.size)
        assertFailsWith<SyncTransportException> { WebDavTransport(http, "file:///data/x", "u", "p").download() }
        assertFailsWith<SyncTransportException> { WebDavTransport(http, "ftp://host/x", "u", "p").download() }
    }
}
