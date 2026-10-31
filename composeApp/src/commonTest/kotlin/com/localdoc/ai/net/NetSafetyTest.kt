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

    // --- DNS-перепривязка: публичное имя, резолвящееся во внутренний адрес (аудит 2.4.0) ---

    @Test
    fun `public name resolving to an internal address is refused`() {
        for (addresses in listOf(
            listOf("192.168.0.1"),
            listOf("8.8.8.8", "127.0.0.1"),
            listOf("::ffff:10.0.0.1"),
            listOf("fd12::1")
        )) {
            assertFailsWith<NetSafety.DnsRebindingException>(addresses.toString()) {
                NetSafety.checkResolved("novel.example", addresses)
            }
        }
    }

    @Test
    fun `public name resolving to public addresses passes`() {
        NetSafety.checkResolved("novel.example", listOf("93.184.216.34"))
        NetSafety.checkResolved("novel.example", listOf("2001:4860:4860::8888"))
        // Несколько адресов — все публичные
        NetSafety.checkResolved("novel.example", listOf("93.184.216.34", "2606:2800:220:1::1"))
    }

    @Test
    fun `internal names are not checked for rebinding`() {
        // Каталог на NAS — пользователь сам его ввёл, внутренний IP ожидаем
        NetSafety.checkResolved("nas.local", listOf("192.168.1.5"))
        NetSafety.checkResolved("192.168.1.5", listOf("192.168.1.5"))
        NetSafety.checkResolved("localhost", listOf("127.0.0.1"))
    }

    // --- Открытый http:// только с явного согласия (аудит 2.4.0) ---

    @Test
    fun `cleartext http for public sites is always refused`() {
        NetSafety.cleartextAllowed = true
        try {
            assertFailsWith<NetSafety.CleartextNotAllowedException> { NetSafety.checkCleartext("http://novel.example/page") }
        } finally {
            NetSafety.cleartextAllowed = false
        }
    }

    @Test
    fun `cleartext http for home network needs consent`() {
        assertFailsWith<NetSafety.CleartextNotAllowedException> { NetSafety.checkCleartext("http://192.168.1.5:8080/opds") }
        NetSafety.cleartextAllowed = true
        try {
            NetSafety.checkCleartext("http://192.168.1.5:8080/opds")
            NetSafety.checkCleartext("http://nas.local/opds")
        } finally {
            NetSafety.cleartextAllowed = false
        }
    }

    @Test
    fun `https is always allowed regardless of consent`() {
        NetSafety.checkCleartext("https://novel.example/page")
        NetSafety.checkCleartext("https://192.168.1.5:8443/opds")
    }
}
