package com.localdoc.ai.net

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.localdoc.ai.book.WebNavigationDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Учётная запись на сайте. Пароль не попадает в `toString`, а значит и в логи. */
@Serializable
data class SiteAccount(val host: String, val login: String, val password: String) {
    override fun toString() = "SiteAccount($host, $login, ***)"
}

/** Что показываем в списке сайтов: без пароля. */
data class SiteSummary(val host: String, val login: String, val hasSession: Boolean)

/**
 * Логины и пароли сайтов — только в [SecretStorage] (Keystore / Keychain). В обычные настройки,
 * URL и журналы они не попадают; «Забыть сайт» стирает и пароль, и cookie сессии.
 */
class SiteVault(private val storage: SecretStorage, private val cookies: CookieJar) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SiteAccount.serializer())
    private val _sites = MutableStateFlow(summaries())
    val sites: StateFlow<List<SiteSummary>> = _sites.asStateFlow()

    private fun accounts(): List<SiteAccount> =
        storage.get(KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun summaries(): List<SiteSummary> {
        val saved = accounts().map { SiteSummary(it.host, it.login, cookies.hasCookies(it.host)) }
        return saved.sortedBy { it.host }
    }

    fun find(host: String): SiteAccount? = accounts().firstOrNull { it.host == CookieJar.normalizeHost(host) }

    fun save(account: SiteAccount) {
        val normalized = account.copy(host = CookieJar.normalizeHost(account.host))
        storage.put(KEY, json.encodeToString(serializer, accounts().filterNot { it.host == normalized.host } + normalized))
        refresh()
    }

    /** Забыть сайт одной кнопкой: пароль и вся сессия. */
    fun forget(host: String) {
        val h = CookieJar.normalizeHost(host)
        val rest = accounts().filterNot { it.host == h }
        if (rest.isEmpty()) storage.remove(KEY) else storage.put(KEY, json.encodeToString(serializer, rest))
        cookies.clear(h)
        refresh()
    }

    fun refresh() {
        _sites.value = summaries()
    }

    private companion object {
        const val KEY = "site_accounts_v1"
    }
}

/** Найденная на странице форма входа. */
class LoginForm(
    val action: String,
    val usesPost: Boolean,
    /** Скрытые и служебные поля формы в порядке следования (токены CSRF и т. п.). */
    val fixedFields: List<Pair<String, String>>,
    val userField: String,
    val passwordField: String,
    val hasCaptcha: Boolean
)

object LoginFormDetector {

    private val USER_NAME = Regex("login|user|email|e-mail|mail|phone|nick|name|логин|почта", RegexOption.IGNORE_CASE)
    private val CAPTCHA = Regex("g-recaptcha|h-captcha|hcaptcha|cf-turnstile|turnstile|data-sitekey|captcha|smartcaptcha|капч", RegexOption.IGNORE_CASE)
    private val REMEMBER = Regex("remember|stay|keep|запомн", RegexOption.IGNORE_CASE)

    /** Форма входа: ровно одно поле пароля (две и больше — регистрация или смена пароля). */
    fun find(doc: Element, pageUrl: String): LoginForm? {
        for (form in doc.select("form")) {
            val passwords = form.select("input[type=password]")
            if (passwords.size != 1) continue
            val password = passwords.first() ?: continue
            val passwordName = password.attr("name").ifBlank { password.id() }.ifBlank { continue }

            val inputs = form.select("input").toList()
            val passwordIndex = inputs.indexOf(password)
            val textInputs = inputs.filterIndexed { i, el ->
                i < passwordIndex && el.attr("type").lowercase().let { it.isEmpty() || it == "text" || it == "email" || it == "tel" || it == "number" }
            }.filter { it.attr("name").isNotBlank() }
            val user = textInputs.firstOrNull { USER_NAME.containsMatchIn(it.attr("name") + " " + it.id() + " " + it.attr("autocomplete")) }
                ?: textInputs.lastOrNull() ?: continue

            val fixed = ArrayList<Pair<String, String>>()
            for (el in inputs) {
                val name = el.attr("name")
                if (name.isBlank() || el === user || el === password) continue
                when (el.attr("type").lowercase()) {
                    "hidden" -> fixed.add(name to el.attr("value"))
                    "checkbox" -> if (REMEMBER.containsMatchIn(name + el.id())) fixed.add(name to el.attr("value").ifBlank { "on" })
                    "submit" -> if (fixed.none { it.first == name }) fixed.add(name to el.attr("value"))
                }
            }
            val action = form.attr("action").trim().let { if (it.isEmpty()) pageUrl else WebNavigationDetector.resolveUrl(pageUrl, it) ?: pageUrl }
            val captcha = CAPTCHA.containsMatchIn(form.outerHtml()) || doc.select("iframe[src*=captcha], iframe[src*=turnstile], .g-recaptcha, .h-captcha, .cf-turnstile").isNotEmpty()
            return LoginForm(
                action = action,
                usesPost = !form.attr("method").equals("get", ignoreCase = true),
                fixedFields = fixed,
                userField = user.attr("name"),
                passwordField = passwordName,
                hasCaptcha = captcha
            )
        }
        return null
    }

    /** Страница-«заглушка» защиты (Cloudflare и подобные): её нельзя пройти из приложения. */
    fun isChallengePage(status: Int, html: String): Boolean {
        val head = html.take(4000).lowercase()
        return (status == 403 || status == 503) && ("just a moment" in head || "cf-chl" in head || "challenge-platform" in head || "checking your browser" in head || "ddos-guard" in head)
    }
}

/** Результат попытки входа. */
sealed interface LoginResult {
    data object Success : LoginResult
    data object WrongCredentials : LoginResult
    /** Нужна проверка, которую приложение не обходит (капча, защита сайта): войдите во встроенном браузере. */
    data class NeedsBrowser(val reason: String) : LoginResult
    data object NoForm : LoginResult
    data class Failed(val message: String) : LoginResult
}

/**
 * Вход на сайты с контентом, закрытым логином. Только легитимный путь: форма входа отправляется как
 * обычный браузер; капчу, paywall и защиту сайта приложение не обходит — при их появлении
 * предлагается войти во встроенном браузере, после чего сессия (cookie) забирается оттуда.
 */
class SiteAuth(private val http: WebHttp, private val vault: SiteVault) {

    suspend fun login(loginPageUrl: String, login: String, password: String, remember: Boolean = true): LoginResult {
        if (!loginPageUrl.startsWith("https://", ignoreCase = true)) {
            return LoginResult.Failed("Вход возможен только на защищённых страницах https://: пароль не отправляется по открытому каналу")
        }
        if (login.isBlank() || password.isEmpty()) return LoginResult.Failed("Введите логин и пароль")
        return try {
            val page = http.get(loginPageUrl)
            val html = page.text()
            if (LoginFormDetector.isChallengePage(page.status, html)) {
                return LoginResult.NeedsBrowser("Сайт требует проверки браузера")
            }
            val form = LoginFormDetector.find(Ksoup.parse(html, page.url), page.url) ?: return LoginResult.NoForm
            if (form.hasCaptcha) return LoginResult.NeedsBrowser("На странице входа есть капча")

            val fields = form.fixedFields + (form.userField to login) + (form.passwordField to password)
            val result = if (form.usesPost) http.postForm(form.action, fields, referer = page.url)
            else http.get(form.action + (if ('?' in form.action) "&" else "?") + WebHttp.formEncode(fields), mapOf("Referer" to page.url))
            val body = result.text()
            when {
                LoginFormDetector.isChallengePage(result.status, body) -> LoginResult.NeedsBrowser("Сайт требует проверки браузера")
                result.status >= 400 -> LoginResult.Failed("Сайт ответил ошибкой HTTP ${result.status}")
                else -> {
                    val again = LoginFormDetector.find(Ksoup.parse(body, result.url), result.url)
                    when {
                        again == null -> {
                            if (remember) vault.save(SiteAccount(CookieJar.hostOf(loginPageUrl), login, password)) else vault.refresh()
                            LoginResult.Success
                        }
                        again.hasCaptcha -> LoginResult.NeedsBrowser("После входа сайт показал капчу")
                        else -> LoginResult.WrongCredentials
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LoginResult.Failed("Не удалось войти: ${e.message ?: "ошибка сети"}")
        }
    }

    /** Повторный вход сохранёнными данными, когда сессия истекла. */
    suspend fun relogin(host: String, loginPageUrl: String): LoginResult {
        val account = vault.find(host) ?: return LoginResult.NoForm
        return login(loginPageUrl, account.login, account.password, remember = false)
    }

    /** Сессия, полученная во встроенном браузере (после капчи): cookie сохраняются, логин — по желанию. */
    fun importBrowserSession(pageUrl: String, cookieHeader: String) {
        http.cookies.importHeader(pageUrl, cookieHeader)
        vault.refresh()
    }

    fun forget(host: String) = vault.forget(host)
}
