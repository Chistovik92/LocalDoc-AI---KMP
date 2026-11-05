package com.localdoc.ai.net

import com.localdoc.ai.domain.CertificateTrustState
import com.localdoc.ai.platform.CertificateProblem
import com.localdoc.ai.platform.RawRequest
import com.localdoc.ai.platform.RawResponse
import com.localdoc.ai.platform.UntrustedCertificateException
import com.localdoc.ai.platform.sha256Hex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CertificateTrustTest {
    @Test
    fun `sha256 fingerprint is lowercase hex`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
    }

    @Test
    fun `trust is per site and replaced when the certificate changes`() {
        val state = CertificateTrustState().trusting("novel.example", "aa").trusting("other.example:8443", "bb")
        assertEquals(setOf("aa"), state.allowanceFor("novel.example").fingerprints)
        assertTrue(state.allowanceFor("unknown.example").fingerprints.isEmpty())

        val rotated = state.trusting("novel.example", "cc")
        assertEquals(setOf("cc"), rotated.allowanceFor("novel.example").fingerprints)
        assertEquals(setOf("bb"), rotated.allowanceFor("other.example:8443").fingerprints)
    }

    @Test
    fun `forgetting a site removes its trust and the any switch is independent`() {
        val state = CertificateTrustState(allowAny = true).trusting("novel.example", "aa").forgetting("novel.example")
        assertTrue(state.allowanceFor("novel.example").fingerprints.isEmpty())
        assertTrue(state.allowanceFor("novel.example").any)
        assertFalse(CertificateTrustState().allowanceFor("novel.example").any)
    }

    @Test
    fun `web http hands the sites allowance to every request including redirect targets`() = runTest {
        val transport = FakeTransport { request ->
            if (request.url.startsWith("https://a.example")) redirect("https://b.example/next") else page("<p>ok</p>")
        }
        val trust = CertificateTrustState().trusting("a.example", "aa").trusting("b.example", "bb")
        val http = WebHttp(transport, CookieJar(MemoryStorage()), HostThrottle(intervalMs = 0, sleep = {}), certificates = trust::allowanceFor)

        http.get("https://a.example/start")

        assertEquals(setOf("aa"), transport.requests[0].trustedCertificates)
        assertEquals(setOf("bb"), transport.requests[1].trustedCertificates)
        assertFalse(transport.requests[1].trustAnyCertificate)
    }

    @Test
    fun `untrusted certificate error reaches the caller unwrapped`() = runTest {
        val transport = object : HttpTransport {
            override val userAgent = "TestAgent/1.0"
            override suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse =
                throw UntrustedCertificateException("self-signed.example", "ff", CertificateProblem.UNKNOWN_ISSUER)
        }
        val http = WebHttp(transport, CookieJar(MemoryStorage()), HostThrottle(intervalMs = 0, sleep = {}))

        val error = assertFailsWith<UntrustedCertificateException> { http.get("https://self-signed.example/") }
        assertEquals("ff", error.fingerprint)
        assertEquals(CertificateProblem.UNKNOWN_ISSUER, error.problem)
    }
}
