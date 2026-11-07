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

class GoogleDriveTransportTest {
    private class Fake(val handler: (RawRequest) -> RawResponse) : HttpTransport {
        val requests = ArrayList<RawRequest>()
        override val userAgent = "test"
        override suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse {
            requests += request
            return handler(request)
        }
    }
    private fun response(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) =
        RawResponse(status, headers, emptyList(), body.encodeToByteArray())
    private fun transport(fake: Fake) = GoogleDriveTransport(fake, { "token" })

    @Test fun `download finds appData file and returns its revision`() = runTest {
        val fake = Fake { request ->
            if (request.method == "GET" && request.url.contains("alt=media")) response(200, "payload")
            else response(200, """{"files":[{"id":"abc","headRevisionId":"7"}]}""")
        }
        val file = transport(fake).download()
        assertContentEquals("payload".encodeToByteArray(), file?.bytes)
        assertEquals("7", file?.version)
        assertTrue(fake.requests[0].url.contains("appDataFolder"))
        assertEquals("Bearer token", fake.requests[1].headers["Authorization"])
    }

    @Test fun `missing file and multipart creation`() = runTest {
        val fake = Fake { request ->
            if (request.method == "GET") response(200, """{"files":[]}""")
            else response(200, """{"id":"new","headRevisionId":"1"}""")
        }
        assertNull(transport(fake).download())
        assertEquals("1", transport(fake).upload(byteArrayOf(1, 2), null))
        val upload = fake.requests.last()
        assertEquals("POST", upload.method)
        assertTrue(upload.body!!.decodeToString().contains("appDataFolder"))
    }

    @Test fun `changed revision is a conflict and connection errors are readable`() = runTest {
        val fake = Fake { response(200, """{"files":[{"id":"abc","headRevisionId":"8"}]}""") }
        assertFailsWith<UploadConflictException> { transport(fake).upload(byteArrayOf(1), "7") }
        val broken = Fake { throw IllegalStateException("offline") }
        assertFailsWith<SyncTransportException> { transport(broken).download() }
    }

    @Test fun `multiple matches are rejected instead of arbitrary choice`() = runTest {
        val fake = Fake { response(200, """{"files":[{"id":"a"},{"id":"b"}]}""") }
        assertFailsWith<SyncTransportException> { transport(fake).download() }
    }
}
