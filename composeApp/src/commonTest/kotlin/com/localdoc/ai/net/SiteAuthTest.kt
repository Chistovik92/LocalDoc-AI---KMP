package com.localdoc.ai.net

import com.localdoc.ai.platform.RawRequest
import com.localdoc.ai.platform.RawResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Хранилище секретов в памяти. */
class MemoryStorage : SecretStorage {
    val data = HashMap<String, String>()
    override fun get(key: String) = data[key]
    override fun put(key: String, value: String) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}

/** Подделка транспорта: обработчик получает запрос и возвращает ответ; все запросы запоминаются. */
class FakeTransport(private val handler: (RawRequest) -> RawResponse) : HttpTransport {
    override val userAgent = "TestAgent/1.0"
    val requests = ArrayList<RawRequest>()
    override suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse {
        requests.add(request)
        return handler(request)
    }
}

internal fun page(html: String, status: Int = 200, cookies: List<String> = emptyList(), headers: Map<String, String> = emptyMap()) =
    RawResponse(status, headers, cookies, html.encodeToByteArray())

internal fun redirect(to: String, cookies: List<String> = emptyList(), status: Int = 302) =
    RawResponse(status, mapOf("location" to to), cookies, ByteArray(0))

class SiteAuthTest {

    private val loginHtml = """<html><body><form action="/do-login" method="post">
        <input type="hidden" name="csrf" value="tok123"/><input type="text" name="username"/>
        <input type="password" name="password"/><input type="checkbox" name="remember" value="1"/>
        <input type="submit" name="go" value="Войти"/></form></body></html>"""

    private fun setup(handler: (RawRequest) -> RawResponse): Triple<FakeTransport, WebHttp, SiteAuth> {
        val transport = FakeTransport(handler)
        val storage = MemoryStorage()
        val jar = CookieJar(storage) { 1_000_000_000_000L }
        val http = WebHttp(transport, jar)
        return Triple(transport, http, SiteAuth(http, SiteVault(storage, jar)))
    }

    @Test
    fun `login posts the form with csrf and keeps the session cookie`() = runTest {
        val (transport, http, auth) = setup { req ->
            when {
                req.url == "https://novel.example/login" -> page(loginHtml, cookies = listOf("pre=1; Path=/"))
                req.url == "https://novel.example/do-login" && req.method == "POST" ->
                    redirect("https://novel.example/library", cookies = listOf("sid=secret42; Path=/; HttpOnly; Secure"))
                req.url == "https://novel.example/library" -> page("<html><body><h1>Моя библиотека</h1></body></html>")
                else -> page("", status = 404)
            }
        }
        val result = auth.login("https://novel.example/login", "reader", "p@ss word")
        assertEquals(LoginResult.Success, result)

        val post = transport.requests.first { it.method == "POST" }
        val body = post.body!!.decodeToString()
        assertTrue("csrf=tok123" in body && "username=reader" in body && "password=p%40ss+word" in body && "remember=1" in body, body)
        assertEquals("pre=1", post.headers["Cookie"])
        // Следующий запрос уже идёт с сессией, а на чужой хост cookie не уходят
        assertTrue("sid=secret42" in transport.requests.last().headers["Cookie"].orEmpty())
        assertNull(http.cookies.header("https://other.example/"))
        assertEquals("pre=1", http.cookies.header("http://novel.example/"), "Secure-cookie не отправляется по http")
    }

    @Test
    fun `wrong password is reported and nothing is saved`() = runTest {
        val (_, http, auth) = setup { req -> if (req.method == "POST") page(loginHtml + "<p>Неверный пароль</p>") else page(loginHtml) }
        assertEquals(LoginResult.WrongCredentials, auth.login("https://novel.example/login", "reader", "bad"))
        assertFalse(http.cookies.hasCookies("novel.example"))
    }

    @Test
    fun `captcha and browser challenge are never bypassed`() = runTest {
        val captcha = loginHtml.replace("</form>", "<div class=\"g-recaptcha\" data-sitekey=\"k\"></div></form>")
        val (transport, _, auth) = setup { page(captcha) }
        assertTrue(auth.login("https://novel.example/login", "a", "b") is LoginResult.NeedsBrowser)
        assertTrue(transport.requests.none { it.method == "POST" }, "форма с капчей не отправляется")

        val (_, _, authCf) = setup { page("<html><title>Just a moment...</title></html>", status = 503) }
        assertTrue(authCf.login("https://novel.example/login", "a", "b") is LoginResult.NeedsBrowser)
    }

    @Test
    fun `password is never sent over plain http and missing form is reported`() = runTest {
        val (transport, _, auth) = setup { page("<html><body>нет формы</body></html>") }
        assertTrue(auth.login("http://novel.example/login", "a", "b") is LoginResult.Failed)
        assertTrue(transport.requests.isEmpty())
        assertEquals(LoginResult.NoForm, auth.login("https://novel.example/login", "a", "b"))
    }

    @Test
    fun `forget removes password and session`() {
        val storage = MemoryStorage()
        val jar = CookieJar(storage) { 1_000_000_000_000L }
        val vault = SiteVault(storage, jar)
        vault.save(SiteAccount("www.novel.example", "reader", "secret"))
        jar.store("https://novel.example/", "sid=1; Domain=novel.example; Path=/")
        vault.refresh()
        assertEquals("reader", vault.sites.value.single().login)
        assertTrue(vault.sites.value.single().hasSession)

        vault.forget("novel.example")
        assertTrue(vault.sites.value.isEmpty())
        assertNull(vault.find("novel.example"))
        assertFalse(jar.hasCookies("novel.example"))
        assertTrue(storage.data.values.none { "secret" in it })
    }

    @Test
    fun `unsafe form actions and GET never receive credentials`() = runTest {
        for (html in listOf(
            loginHtml.replace("/do-login", "http://novel.example/do-login"),
            loginHtml.replace("/do-login", "https://evil.example/collect"),
            loginHtml.replace("method=\"post\"", "method=\"get\"")
        )) {
            val (transport, _, auth) = setup { page(html) }
            assertTrue(auth.login("https://novel.example/login", "reader", "secret") is LoginResult.NeedsBrowser)
            assertEquals(1, transport.requests.size)
            assertNull(transport.requests.single().body)
        }
    }

    @Test
    fun `disappearing signup form does not prove success or save password`() = runTest {
        val storage = MemoryStorage()
        val jar = CookieJar(storage)
        val vault = SiteVault(storage, jar)
        val form = loginHtml.replace("<input type=\"checkbox\" name=\"remember\" value=\"1\"/>", "")
            .replace("</form>", "<input type=\"password\" name=\"confirm\"/></form>")
        val transport = FakeTransport { if (it.method == "POST") page("Check your email") else page(form) }
        val auth = SiteAuth(WebHttp(transport, jar), vault)
        assertTrue(auth.signup("https://novel.example/register", "reader", "r@example.org", "secret") is SignupResult.NeedsBrowser)
        assertNull(vault.find("novel.example"))
        assertEquals(2, transport.requests.size, "no guessed auto-login")
    }

    @Test
    fun `signup does not automatically agree to terms or subscriptions`() = runTest {
        val form = loginHtml.replace("</form>", "<input type=\"password\" name=\"confirm\"/><input type=\"checkbox\" name=\"terms\"/></form>")
        val (transport, _, auth) = setup { page(form) }
        assertTrue(auth.signup("https://novel.example/register", "reader", "r@example.org", "secret") is SignupResult.NeedsBrowser)
        assertTrue(transport.requests.none { it.method == "POST" })
    }

    @Test
    fun `secrets do not leak through toString`() {
        assertFalse("secret" in SiteAccount("h", "u", "secret").toString())
        assertFalse("tokenvalue" in StoredCookie("sid", "tokenvalue", "h", expiresAt = 1).toString())
    }

    @Test
    fun `cookie jar scopes domain path expiry and survives restart`() {
        val storage = MemoryStorage()
        val time = 1_000_000_000_000L
        val jar = CookieJar(storage) { time }
        jar.store("https://a.example.com/x/y", "t=1; Domain=example.com; Path=/x; Max-Age=100")
        jar.store("https://a.example.com/", "host=2")
        jar.store("https://a.example.com/", "evil=3; Domain=other.com")
        jar.store("https://a.example.com/", "pub=4; Domain=com")
        assertEquals("t=1", jar.header("https://b.example.com/x/z"))
        assertEquals("host=2", jar.header("https://a.example.com/"))
        assertNull(jar.header("https://example.org/"))
        assertTrue(storage.data.values.none { "evil" in it || "pub=" in it })

        assertEquals("t=1", CookieJar(storage) { time }.header("https://example.com/x"))
        assertNull(CookieJar(storage) { time + 200_000 }.header("https://example.com/x"), "Max-Age истёк")
    }

    @Test
    fun `host only cookies do not leak between www and the apex host`() {
        val jar = CookieJar(MemoryStorage()) { 1_000_000_000_000L }
        jar.store("https://www.example.com/", "a=1")
        assertEquals("a=1", jar.header("https://www.example.com/"))
        assertNull(jar.header("https://example.com/"), "cookie без Domain принадлежит только www")
        jar.store("https://example.com/", "b=2")
        assertEquals("b=2", jar.header("https://example.com/"))
        assertEquals("a=1", jar.header("https://www.example.com/"))
        jar.store("https://www.example.com/", "c=3; Domain=example.com")
        assertTrue("c=3" in jar.header("https://example.com/").orEmpty(), "с Domain cookie видна всему домену")
        jar.clear("example.com")
        assertFalse(jar.hasCookies("example.com") || jar.hasCookies("www.example.com"))
    }

    @Test
    fun `cookies for public suffixes and shared hosting are refused`() {
        val jar = CookieJar(MemoryStorage()) { 1_000_000_000_000L }
        jar.store("https://alice.github.io/", "x=1; Domain=github.io")
        jar.store("https://shop.co.uk/", "y=1; Domain=co.uk")
        jar.store("https://blog.blogspot.com/", "z=1; Domain=blogspot.com")
        jar.store("https://a.example.co.uk/", "ok=1; Domain=example.co.uk")
        assertNull(jar.header("https://bob.github.io/"))
        assertNull(jar.header("https://other.co.uk/"))
        assertNull(jar.header("https://other.blogspot.com/"))
        assertEquals("ok=1", jar.header("https://b.example.co.uk/"))
        assertTrue(PublicSuffixes.isPublicSuffix("com") && PublicSuffixes.isPublicSuffix("com.br") && !PublicSuffixes.isPublicSuffix("example.org"))
    }

    @Test
    fun `expires dates are parsed and past dates delete cookies`() {
        assertEquals(1_000_000_000_000L, CookieJar.parseHttpDate("Sun, 09 Sep 2001 01:46:40 GMT"))
        assertNotNull(CookieJar.parseHttpDate("Wed, 21-Oct-26 07:28:00 GMT"))
        val jar = CookieJar(MemoryStorage()) { 1_000_000_000_000L }
        jar.store("https://x.example/", "a=1; Expires=Sun, 09 Sep 2001 01:46:40 GMT")
        assertNull(jar.header("https://x.example/"))
    }

    @Test
    fun `redirects follow with cookies per hop`() = runTest {
        val transport = FakeTransport { req ->
            when (req.url) {
                "https://s.example/a" -> redirect("/b", cookies = listOf("c1=1; Path=/"))
                "https://s.example/b" -> redirect("https://t.example/c", status = 302)
                "https://t.example/c" -> page("конец")
                else -> page("", 404)
            }
        }
        val http = WebHttp(transport, CookieJar(MemoryStorage()))
        val result = http.get("https://s.example/a")
        assertEquals("https://t.example/c", result.url)
        assertEquals("конец", result.text())
        assertEquals("c1=1", transport.requests[1].headers["Cookie"])
        assertNull(transport.requests[2].headers["Cookie"], "cookie s.example не уходят на t.example")

        val loop = WebHttp(FakeTransport { redirect("https://s.example/a") }, CookieJar(MemoryStorage()))
        assertFailsWith<IllegalStateException> { loop.get("https://s.example/a") }
        assertFailsWith<IllegalArgumentException> { loop.get("ftp://s.example/a") }
    }

    @Test
    fun `browser session cookies are imported for the host`() {
        val jar = CookieJar(MemoryStorage()) { 1_000_000_000_000L }
        jar.importHeader("https://novel.example/login", "cf_clearance=abc; sid=xyz")
        assertEquals("cf_clearance=abc; sid=xyz", jar.header("https://novel.example/book"))
    }
}
