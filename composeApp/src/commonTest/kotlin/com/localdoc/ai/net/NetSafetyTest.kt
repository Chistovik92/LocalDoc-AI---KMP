package com.localdoc.ai.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Аудит безопасности 2.0.0: какие адреса считаются «внутренними» и какие перенаправления запрещены. */
class NetSafetyTest {

    @Test
    fun `host is extracted without user info, port and brackets`() {
        assertEquals("example.com", NetSafety.hostOf("https://user:pw@Example.COM:8443/path?q=1#x"))
        assertEquals("::1", NetSafety.hostOf("http://[::1]:8080/"))
        assertEquals("host.test", NetSafety.hostOf("https://host.test"))
    }

    @Test
    fun `loopback, link-local and private ranges are internal`() {
        val internal = listOf(
            "localhost", "LOCALHOST", "printer.localhost", "nas.local",
            "127.0.0.1", "127.255.255.254", "0.0.0.0", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.10",
            "169.254.169.254", "100.64.0.1",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:192.168.0.1"
        )
        for (host in internal) assertTrue(NetSafety.isInternalHost(host), host)
    }

    @Test
    fun `alternative spellings of internal IPv4 are recognised`() {
        // Целое число, шестнадцатеричная и восьмеричная записи — стандартные обходы проверок SSRF
        for (host in listOf("2130706433", "0x7f000001", "0177.0.0.1", "127.1", "0xa9fea9fe", "3232235777")) {
            assertTrue(NetSafety.isInternalHost(host), host)
        }
    }

    @Test
    fun `public hosts are not internal`() {
        val publicHosts = listOf(
            "example.com", "www.gutenberg.org", "8.8.8.8", "172.32.0.1", "172.15.255.255", "100.128.0.1",
            "127.0.0.1.evil.com", "localhost.example.com", "2001:4860:4860::8888", "::ffff:8.8.8.8", "11.0.0.1"
        )
        for (host in publicHosts) assertFalse(NetSafety.isInternalHost(host), host)
    }

    @Test
    fun `redirect from a public site to an internal address is refused`() {
        for (target in listOf(
            "http://169.254.169.254/latest/meta-data/", "http://localhost:8080/admin", "https://192.168.0.1/", "http://[::1]/",
            "http://2130706433/"
        )) {
            assertFailsWith<UnsafeRedirectException>(target) { NetSafety.checkRedirect("https://novel.example/a", target) }
        }
    }

    @Test
    fun `redirect that downgrades https to http on a public host is refused`() {
        assertFailsWith<UnsafeRedirectException> { NetSafety.checkRedirect("https://novel.example/a", "http://novel.example/a") }
        assertFailsWith<UnsafeRedirectException> { NetSafety.checkRedirect("https://novel.example/a", "http://other.example/b") }
    }

    @Test
    fun `ordinary redirects and home network catalogs keep working`() {
        NetSafety.checkRedirect("https://novel.example/a", "https://cdn.example/b")
        NetSafety.checkRedirect("http://novel.example/a", "https://novel.example/a")
        NetSafety.checkRedirect("http://novel.example/a", "http://novel.example/b")
        // Каталог Calibre в домашней сети может перенаправлять внутри неё
        NetSafety.checkRedirect("http://192.168.1.5:8080/opds", "http://192.168.1.5:8080/opds/new")
        NetSafety.checkRedirect("http://nas.local/opds", "http://192.168.1.5/opds")
    }
}
