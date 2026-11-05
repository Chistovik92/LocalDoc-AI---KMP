package com.localdoc.ai.domain

import com.localdoc.ai.i18n.L10n
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.LibraryRepository
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.book.WebNavigation
import com.localdoc.ai.net.CookieJar
import com.localdoc.ai.net.LoginResult
import com.localdoc.ai.net.SiteAuth
import com.localdoc.ai.net.SiteVault
import com.localdoc.ai.parser.LoginRequiredException
import com.localdoc.ai.parser.WebArticleParser
import com.localdoc.ai.platform.CertificateProblem
import com.localdoc.ai.platform.UntrustedCertificateException
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.ai_nav_next_chapter
import com.localdoc.ai.resources.ai_nav_toc
import com.localdoc.ai.resources.web_login_needs_browser
import com.localdoc.ai.resources.web_login_no_form
import com.localdoc.ai.resources.web_login_no_session
import com.localdoc.ai.resources.web_login_wrong_credentials
import com.localdoc.ai.resources.web_no_text_on_page
import com.localdoc.ai.resources.web_open_failed
import com.localdoc.ai.resources.web_page_not_in_cache
import com.localdoc.ai.resources.web_toc_load_failed
import com.localdoc.ai.resources.web_toc_not_found
import com.localdoc.ai.tts.ReadAloudController
import com.localdoc.ai.tts.ReadAloudStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Открытая веб-страница в режиме чтения. */
data class WebDocument(val book: Book, val entryId: String, val links: List<PageLink>, val startBlock: Int)

/** Страница просит вход: что показать пользователю в окне входа. */
data class LoginRequest(
    val pageUrl: String,
    val loginUrl: String,
    val host: String,
    /** true — сайт требует проверку браузера (капча/защита): вход возможен только во встроенном браузере. */
    val browserOnly: Boolean,
    val savedLogin: String?,
    /** Сообщение последней попытки входа (неверный пароль, ошибка сети…). */
    val message: String? = null
)

/** Сертификат сайта не прошёл проверку: что показать в окне «Доверять этому сайту?». */
data class CertificateRequest(val pageUrl: String, val host: String, val fingerprint: String, val problem: CertificateProblem)

/**
 * Состояние вкладки «Веб»: загрузка страниц, переходы по главам веб-новелл и оглавление.
 * Если на странице нет явных маркеров «Далее / Оглавление», ссылку выбирает локальная модель.
 * Умеет входить на сайты, закрытые логином, и автоматически переходить к следующей главе
 * после озвучки (опция «Автопереход»).
 */
class WebSession(
    private val parser: WebArticleParser,
    private val library: LibraryRepository,
    private val pipeline: () -> SmartPipeline,
    private val isModelLoaded: () -> Boolean,
    private val readAloud: () -> ReadAloudController,
    private val scope: CoroutineScope,
    private val auth: () -> SiteAuth,
    private val vault: () -> SiteVault,
    private val autoNext: () -> Boolean = { false },
    /** Запоминает согласие пользователя на сертификат сайта (сайт, отпечаток SHA-256). */
    private val trustCertificate: (String, String) -> Unit = { _, _ -> }
) {
    private val _document = MutableStateFlow<WebDocument?>(null)
    val document: StateFlow<WebDocument?> = _document.asStateFlow()

    private val _loading = MutableStateFlow<String?>(null)
    val loading: StateFlow<String?> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toc = MutableStateFlow<List<PageLink>>(emptyList())
    val toc: StateFlow<List<PageLink>> = _toc.asStateFlow()

    private val _tocLoading = MutableStateFlow(false)
    val tocLoading: StateFlow<Boolean> = _tocLoading.asStateFlow()

    private val _loginRequest = MutableStateFlow<LoginRequest?>(null)
    val loginRequest: StateFlow<LoginRequest?> = _loginRequest.asStateFlow()

    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy.asStateFlow()

    private val _certificateRequest = MutableStateFlow<CertificateRequest?>(null)
    val certificateRequest: StateFlow<CertificateRequest?> = _certificateRequest.asStateFlow()

    private var job: Job? = null

    /** Пользователь согласился доверять сертификату сайта: согласие запоминается, страница открывается заново. */
    fun acceptCertificate() {
        val request = _certificateRequest.value ?: return
        _certificateRequest.value = null
        trustCertificate(request.host, request.fingerprint)
        open(request.pageUrl)
    }

    fun dismissCertificate() {
        _certificateRequest.value = null
    }

    fun docKey(doc: WebDocument) = "web:${doc.entryId}"

    /** Подписка на конец озвучки создаётся лениво при первом открытии страницы (плеер — тоже ленивый). */
    private var watching = false

    private fun watchSpeechEnd() {
        if (watching) return
        watching = true
        scope.launch {
            readAloud().state.collect { state ->
                val doc = _document.value ?: return@collect
                if (!autoNext() || state.stoppedAt != ReadAloudStop.BOOK_END) return@collect
                if (readAloud().documentKey != docKey(doc)) return@collect
                if (doc.book.web?.next != null && _loading.value == null) next()
            }
        }
    }

    fun open(url: String) = open(url, allowRelogin = true)

    private fun open(url: String, allowRelogin: Boolean) {
        watchSpeechEnd()
        job?.cancel()
        val previousKey = _document.value?.let(::docKey)
        job = scope.launch {
            _loading.value = url
            _error.value = null
            try {
                val page = parser.load(url)
                require(page.book.blocks.isNotEmpty()) { L10n.get(Res.string.web_no_text_on_page) }
                var book = page.book
                // Нет маркера «следующая глава» — спрашиваем локальную модель
                if (book.web?.next == null && isModelLoaded() && looksLikeChapter(book)) {
                    val next = runCatching { pipeline().pickNavigationLink(page.links, L10n.get(Res.string.ai_nav_next_chapter)) }.getOrNull()
                    if (next != null && next != book.source) book = book.copy(web = (book.web ?: WebNavigation()).copy(next = next))
                }
                val entry = library.add(book)
                val doc = WebDocument(book, entry.id, page.links, entry.progressBlock)
                _document.value = doc
                _toc.value = emptyList()
                _loginRequest.value = null
                // Пользователь сам перешёл к следующей главе (или автопереход) во время озвучки — продолжаем читать её.
                // Через startReading, а не сырые блоки: так при включённом переводе озвучивается перевод, а не оригинал.
                val player = readAloud()
                if (previousKey != null && player.documentKey == previousKey) {
                    AppGraph.startReading(docKey(doc), book, 0)
                }
            } catch (e: LoginRequiredException) {
                handleLoginRequired(url, e, allowRelogin)
            } catch (e: UntrustedCertificateException) {
                _certificateRequest.value = CertificateRequest(url, e.host, e.fingerprint, e.problem)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = L10n.get(Res.string.web_open_failed, t.message ?: t::class.simpleName ?: "")
            } finally {
                _loading.value = null
            }
        }
    }

    private suspend fun handleLoginRequired(url: String, e: LoginRequiredException, allowRelogin: Boolean) {
        val host = e.host
        val loginUrl = e.loginUrl ?: defaultLoginUrl(e.pageUrl)
        val saved = vault().find(host)
        if (saved != null && allowRelogin && !e.browserOnly) {
            // Сессия истекла — один раз входим сохранёнными данными и повторяем загрузку
            if (auth().relogin(host, loginUrl) == LoginResult.Success) {
                open(url, allowRelogin = false)
                return
            }
        }
        _loginRequest.value = LoginRequest(url, loginUrl, host, e.browserOnly, saved?.login)
    }

    private fun defaultLoginUrl(pageUrl: String): String {
        val origin = pageUrl.substringBefore("://") + "://" + pageUrl.substringAfter("://").substringBefore('/')
        return "$origin/login"
    }

    /** Вход логином и паролем со страницы входа [loginUrl]; при успехе страница открывается заново. */
    fun submitLogin(loginUrl: String, login: String, password: String) {
        val request = _loginRequest.value ?: return
        scope.launch {
            _loginBusy.value = true
            try {
                when (val result = auth().login(loginUrl, login.trim(), password)) {
                    LoginResult.Success -> {
                        _loginRequest.value = null
                        open(request.pageUrl, allowRelogin = false)
                    }
                    LoginResult.WrongCredentials -> updateLogin(request, loginUrl, L10n.get(Res.string.web_login_wrong_credentials))
                    LoginResult.NoForm ->
                        updateLogin(request, loginUrl, L10n.get(Res.string.web_login_no_form))
                    is LoginResult.NeedsBrowser ->
                        _loginRequest.value = request.copy(loginUrl = loginUrl, browserOnly = true, message = result.reason + ": " + L10n.get(Res.string.web_login_needs_browser))
                    is LoginResult.Failed -> updateLogin(request, loginUrl, result.message)
                }
            } finally {
                _loginBusy.value = false
            }
        }
    }

    private fun updateLogin(request: LoginRequest, loginUrl: String, message: String) {
        _loginRequest.value = request.copy(loginUrl = loginUrl, message = message)
    }

    /** Сессия получена во встроенном браузере (после капчи): забираем cookie и открываем страницу. */
    fun finishBrowserLogin(browserUrl: String, cookieHeader: String) {
        val request = _loginRequest.value ?: return
        if (cookieHeader.isBlank()) {
            scope.launch { _loginRequest.value = request.copy(message = L10n.get(Res.string.web_login_no_session)) }
            return
        }
        auth().importBrowserSession(browserUrl, cookieHeader)
        // Сессия могла быть выдана другому адресу того же сайта (www-вариант, SSO внутри домена):
        // cookie копируются и за хостом страницы, но только для родственных хостов — сессия
        // с постороннего домена на хост страницы не переносится
        val browserHost = CookieJar.normalizeHost(CookieJar.hostOf(browserUrl))
        if (browserHost != CookieJar.normalizeHost(request.host) && sameSite(browserHost, CookieJar.normalizeHost(request.host))) {
            auth().importBrowserSession(request.pageUrl, cookieHeader)
        }
        _loginRequest.value = null
        open(request.pageUrl, allowRelogin = false)
    }

    /** Родственные ли хосты одного сайта: один является суффиксом другого на границе домена. */
    private fun sameSite(first: String, second: String): Boolean =
        first.endsWith(".$second") || second.endsWith(".$first")

    fun dismissLogin() {
        _loginRequest.value = null
    }

    /** Открытие сохранённой страницы из библиотеки без сети. */
    fun openSaved(entryId: String) {
        job?.cancel()
        job = scope.launch {
            val book = library.load(entryId)
            if (book == null) {
                _error.value = L10n.get(Res.string.web_page_not_in_cache)
                return@launch
            }
            library.touch(entryId)
            _document.value = WebDocument(book, entryId, emptyList(), library.entry(entryId)?.progressBlock ?: 0)
            _toc.value = emptyList()
        }
    }

    fun next() = _document.value?.book?.web?.next?.let(::open)
    fun previous() = _document.value?.book?.web?.prev?.let(::open)

    fun close() {
        job?.cancel()
        _document.value = null
        _toc.value = emptyList()
    }

    fun saveProgress(block: Int) {
        _document.value?.let { library.saveProgress(it.entryId, block) }
    }

    /** Оглавление новеллы: страница по ссылке «Оглавление», иначе — подсказка модели или ссылки текущей страницы. */
    fun loadTableOfContents() {
        val doc = _document.value ?: return
        scope.launch {
            _tocLoading.value = true
            try {
                _toc.value = resolveTableOfContents(doc)
                if (_toc.value.isEmpty()) _error.value = L10n.get(Res.string.web_toc_not_found)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = L10n.get(Res.string.web_toc_load_failed, t.message ?: "")
            } finally {
                _tocLoading.value = false
            }
        }
    }

    /**
     * Список глав без листания книги: страница оглавления (со всеми её частями), найденная по ссылке
     * «Оглавление» или подсказке модели; запасной вариант — похожие на главы ссылки текущей страницы.
     */
    suspend fun resolveTableOfContents(doc: WebDocument): List<PageLink> {
        var tocUrl = doc.book.web?.toc
        if (tocUrl == null && isModelLoaded() && doc.links.isNotEmpty()) {
            tocUrl = runCatching { pipeline().pickNavigationLink(doc.links, L10n.get(Res.string.ai_nav_toc)) }.getOrNull()
        }
        val fromPage = if (tocUrl != null) parser.loadTableOfContents(tocUrl) else emptyList()
        // Ссылки страницы ГЛАВЫ («Назад», «Далее», ближайшие главы) оглавлением не считаются — иначе скачивание
        // пошло бы только по ним; без оглавления книга идёт от первой главы по ссылкам «Назад» и «Далее»
        return fromPage.ifEmpty {
            // Страница книги с адресом вида /book/123 — не глава: её список глав остаётся оглавлением
            if (doc.links.isNotEmpty() && !NovelTitles.looksLikeChapter(doc.book.title, doc.book.source)) {
                chapterLikeLinks(doc.links, doc.book.source)
            } else emptyList()
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun looksLikeChapter(book: Book): Boolean =
        CHAPTER_HINT.containsMatchIn(book.title) || CHAPTER_HINT.containsMatchIn(book.source)

    private fun chapterLikeLinks(links: List<PageLink>, pageUrl: String): List<PageLink> {
        val host = pageUrl.substringAfter("://").substringBefore('/')
        return links.filter { it.url.contains(host) && CHAPTER_HINT.containsMatchIn(it.text) }.distinctBy { it.url }
    }

    private companion object {
        val CHAPTER_HINT = Regex("глава|chapter|ch\\.?\\s*\\d|том|часть|/\\d+/?$", RegexOption.IGNORE_CASE)
    }
}
