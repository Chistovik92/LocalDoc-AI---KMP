package com.localdoc.ai.ui

import com.localdoc.ai.resources.b
import com.localdoc.ai.resources.cancel
import com.localdoc.ai.resources.clear
import com.localdoc.ai.resources.delete
import com.localdoc.ai.resources.downloaded_novel_pages
import com.localdoc.ai.resources.forget
import com.localdoc.ai.resources.forget_2
import com.localdoc.ai.resources.forget_site
import com.localdoc.ai.resources.if_a_site_asks_you_to
import com.localdoc.ai.resources.image_cache
import com.localdoc.ai.resources.images_from_the_internet_are_cached
import com.localdoc.ai.resources.kb
import com.localdoc.ai.resources.mb_2
import com.localdoc.ai.resources.no_sign_ins_yet
import com.localdoc.ai.resources.session_active
import com.localdoc.ai.resources.sign_in_again
import com.localdoc.ai.resources.sign_ins_to_web_novel_and
import com.localdoc.ai.resources.the_saved_login_password_and_session
import com.localdoc.ai.resources.open_site_login
import com.localdoc.ai.resources.site_signup_page
import com.localdoc.ai.resources.sign_in_on_site
import com.localdoc.ai.resources.sign_up_2
import com.localdoc.ai.resources.signup_done_sign_in
import com.localdoc.ai.resources.signup_email
import com.localdoc.ai.resources.signup_login_or_nick
import com.localdoc.ai.resources.signup_no_form
import com.localdoc.ai.resources.signup_password_min_6
import com.localdoc.ai.resources.signup_wrong_data
import com.localdoc.ai.resources.site_login_hint
import com.localdoc.ai.resources.site_auth_https_only
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.localdoc.ai.net.CookieJar
import com.localdoc.ai.net.SignupResult
import com.localdoc.ai.net.LoginResult
import com.localdoc.ai.i18n.L10n
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import com.localdoc.ai.resources.done
import com.localdoc.ai.resources.sign_in
import com.localdoc.ai.resources.web_login_needs_browser
import com.localdoc.ai.resources.web_login_no_form
import com.localdoc.ai.resources.web_login_wrong_credentials
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел «Сайты и вход»: сохранённые входы, а пока открыта страница сайта — поля входа и регистрации. */
@Composable
fun SitesSection() {
    val sites by AppGraph.siteVault.sites.collectAsState()
    val document by AppGraph.webSession.document.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AppGraph.siteVault.refresh() }

    // Пока на вкладке «Веб» открыта страница сайта, её вход и регистрация доступны прямо здесь
    val siteHost = remember(document) {
        val url = document?.book?.source.orEmpty()
        if (url.startsWith("http", ignoreCase = true)) CookieJar.normalizeHost(CookieJar.hostOf(url)) else ""
    }

    Text(
        stringResource(Res.string.sign_ins_to_web_novel_and),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (siteHost.isNotBlank()) SiteAuthCard(siteHost, document!!.book.source)
    if (sites.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Lock,
            title = stringResource(Res.string.no_sign_ins_yet),
            text = stringResource(Res.string.if_a_site_asks_you_to)
        )
    }
    sites.forEach { site ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(site.host, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        site.login + if (site.hasSession) " " + stringResource(Res.string.session_active) else " " + stringResource(Res.string.sign_in_again),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = { confirm = site.host }) { Text(stringResource(Res.string.forget_site), maxLines = 1, softWrap = false) }
            }
        }
    }
    confirm?.let { host ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(Res.string.forget, host)) },
            text = { Text(stringResource(Res.string.the_saved_login_password_and_session)) },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.siteAuth.forget(host)
                    clearBrowserCookies(host)
                    confirm = null
                }) { Text(stringResource(Res.string.forget_2), maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(Res.string.cancel), maxLines = 1, softWrap = false) } }
        )
    }
}

/**
 * Вход и регистрация на сайте открытой страницы. Поля появляются, только пока страница сайта открыта
 * во вкладке «Веб»: логин и пароль не хранятся в настройках и не покидают защищённое хранилище.
 */
@Composable
private fun SiteAuthCard(host: String, pageUrl: String) {
    val scope = rememberCoroutineScope()
    var mode by remember(host) { mutableStateOf(AuthMode.SIGN_IN) }
    var login by remember(host) { mutableStateOf(AppGraph.siteVault.find(host)?.login.orEmpty()) }
    var email by remember(host) { mutableStateOf("") }
    var password by remember(host) { mutableStateOf("") }
    var page by remember(host) { mutableStateOf(defaultAuthUrl(pageUrl, mode)) }
    var busy by remember(host) { mutableStateOf(false) }
    var message by remember(host) { mutableStateOf<String?>(null) }

    LaunchedEffect(mode) { page = defaultAuthUrl(pageUrl, mode) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TabText(stringResource(Res.string.sign_in_on_site), mode == AuthMode.SIGN_IN) { mode = AuthMode.SIGN_IN }
                TabText(stringResource(Res.string.sign_up_2), mode == AuthMode.SIGN_UP) { mode = AuthMode.SIGN_UP }
            }
            Text(stringResource(Res.string.site_login_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(login, { login = it }, label = { Text(stringResource(Res.string.signup_login_or_nick)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (mode == AuthMode.SIGN_UP) {
                OutlinedTextField(email, { email = it }, label = { Text(stringResource(Res.string.signup_email)) }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                password, { password = it }, label = { Text(stringResource(Res.string.signup_password_min_6)) }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(page, { page = it }, label = { Text(stringResource(Res.string.site_signup_page)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("\u2714")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !busy, onClick = { AppGraph.webSession.open(page.trim()) }) { Text(stringResource(Res.string.open_site_login), maxLines = 1, softWrap = false) }
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp))
                Button(enabled = !busy && login.isNotBlank() && password.isNotEmpty(), onClick = {
                    scope.launch {
                        busy = true
                        try {
                            message = run(mode, host, page.trim(), login.trim(), email.trim(), password)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (mode == AuthMode.SIGN_IN) stringResource(Res.string.sign_in) else stringResource(Res.string.sign_up_2), maxLines = 1, softWrap = false) }
            }
            Text(stringResource(Res.string.site_auth_https_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class AuthMode { SIGN_IN, SIGN_UP }

/** Адрес входа или регистрации: страница того же сайта, что и открытая. */
private fun defaultAuthUrl(pageUrl: String, mode: AuthMode): String {
    val origin = pageUrl.substringBefore("://") + "://" + pageUrl.substringAfter("://").substringBefore('/')
    return origin + if (mode == AuthMode.SIGN_IN) "/login" else "/register"
}

private suspend fun run(mode: AuthMode, host: String, page: String, login: String, email: String, password: String): String {
    val auth = AppGraph.siteAuth
    val ok = "\u2714 "
    return try {
        if (mode == AuthMode.SIGN_IN) {
            when (val result = auth.login(page, login, password)) {
                LoginResult.Success -> ok + L10n.get(Res.string.done)
                LoginResult.WrongCredentials -> L10n.get(Res.string.web_login_wrong_credentials)
                LoginResult.NoForm -> L10n.get(Res.string.web_login_no_form)
                is LoginResult.NeedsBrowser -> L10n.get(Res.string.web_login_needs_browser)
                is LoginResult.Failed -> result.message
            }
        } else {
            when (val result = auth.signup(page, login, email, password)) {
                SignupResult.Success -> ok + L10n.get(Res.string.signup_done_sign_in)
                SignupResult.WrongData -> L10n.get(Res.string.signup_wrong_data)
                SignupResult.NoForm -> L10n.get(Res.string.signup_no_form)
                is SignupResult.NeedsBrowser -> L10n.get(Res.string.web_login_needs_browser)
                is SignupResult.Failed -> result.message
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: ""
    }
}

@Composable
private fun TabText(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(start = 4.dp).clickable(onClick = onClick)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** Раздел «Хранилище»: сколько занимают картинки из сети и скачанные страницы веб-новелл, и очистка. */
@Composable
fun StorageSection() {
    val scope = rememberCoroutineScope()
    var imageBytes by remember { mutableStateOf(AppGraph.images.cacheBytes()) }
    var novelBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { novelBytes = withContext(Dispatchers.Default) { novelFolderBytes() } }

    Text(
        stringResource(Res.string.images_from_the_internet_are_cached),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    StorageRow(stringResource(Res.string.image_cache), imageBytes, stringResource(Res.string.clear)) {
        scope.launch {
            AppGraph.images.clearCache()
            imageBytes = AppGraph.images.cacheBytes()
        }
    }
    StorageRow(stringResource(Res.string.downloaded_novel_pages), novelBytes, stringResource(Res.string.delete)) {
        scope.launch {
            withContext(Dispatchers.Default) { AppGraph.files.deleteAppFiles("novels/") }
            novelBytes = 0
        }
    }
}

private fun novelFolderBytes(): Long = AppGraph.files.listAppFiles("novels/").sumOf { AppGraph.files.appFileSize(it) }

@Composable
private fun StorageRow(title: String, bytes: Long, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick, enabled = bytes > 0) { Text(action, maxLines = 1, softWrap = false) }
        }
    }
}

/** «12,3 МБ», «850 КБ». */
@Composable
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> stringResource(Res.string.b, bytes)
    bytes < 1024 * 1024 -> stringResource(Res.string.kb, bytes / 1024)
    else -> {
        val tenths = bytes * 10 / (1024 * 1024)
        stringResource(Res.string.mb_2, tenths / 10, tenths % 10)
    }
}
