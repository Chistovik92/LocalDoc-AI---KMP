package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.LoginRequest
import kotlinx.coroutines.launch

/**
 * Окно «Нужен вход на сайт»: логин и пароль (хранятся только в защищённом хранилище) либо вход во
 * встроенном браузере. Если сайт показывает капчу или проверку, её проходит сам пользователь в окне
 * браузера — приложение ничего не обходит и не решает за вас.
 */
@Composable
fun WebLoginHost() {
    val request by AppGraph.webSession.loginRequest.collectAsState()
    val busy by AppGraph.webSession.loginBusy.collectAsState()
    val current = request ?: return
    var browser by remember(current.pageUrl) { mutableStateOf(current.browserOnly) }
    if (browser) {
        LoginBrowserDialog(current, onDone = { browser = false }, onCancel = { browser = false; if (current.browserOnly) AppGraph.webSession.dismissLogin() })
        return
    }
    LoginDialog(current, busy, onBrowser = { browser = true })
}

@Composable
private fun LoginDialog(request: LoginRequest, busy: Boolean, onBrowser: () -> Unit) {
    var login by remember(request.host) { mutableStateOf(request.savedLogin.orEmpty()) }
    var password by remember(request.host) { mutableStateOf("") }
    var loginUrl by remember(request.host) { mutableStateOf(request.loginUrl) }
    AlertDialog(
        onDismissRequest = { if (!busy) AppGraph.webSession.dismissLogin() },
        title = { Text("Вход на ${request.host}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Эта страница доступна после входа. Логин и пароль хранятся только на устройстве, в защищённом хранилище; " +
                        "их можно стереть кнопкой «Забыть сайт» в Настройках.",
                    style = MaterialTheme.typography.bodySmall
                )
                request.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(login, { login = it }, label = { Text("Логин или e-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(loginUrl, { loginUrl = it }, label = { Text("Страница входа") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("Входим…")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && login.isNotBlank() && password.isNotEmpty(), onClick = { AppGraph.webSession.submitLogin(loginUrl.trim(), login, password) }) {
                Text("Войти", maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = onBrowser) { Text("В браузере", maxLines = 1, softWrap = false) }
                TextButton(enabled = !busy, onClick = { AppGraph.webSession.dismissLogin() }) { Text("Отмена", maxLines = 1, softWrap = false) }
            }
        }
    )
}

/** Встроенный браузер со страницей входа на весь экран: капчу и проверки проходит пользователь. */
@Composable
private fun LoginBrowserDialog(request: LoginRequest, onDone: () -> Unit, onCancel: () -> Unit) {
    val controller = remember { LoginBrowserController() }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Text(
                    if (request.browserOnly) "Сайт просит пройти проверку (например, капчу). Пройдите её сами и войдите, затем нажмите «Готово»."
                    else "Войдите на сайт, затем нажмите «Готово» — приложение заберёт сессию.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
                request.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
                LoginBrowser(url = request.loginUrl, controller = controller, modifier = Modifier.weight(1f).fillMaxWidth())
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    TextButton(onClick = onCancel) { Text("Отмена", maxLines = 1, softWrap = false) }
                    TextButton(onClick = {
                        scope.launch {
                            val url = controller.currentUrl.ifBlank { request.loginUrl }
                            val cookies = controller.cookies()
                            AppGraph.webSession.finishBrowserLogin(url, cookies)
                            onDone()
                        }
                    }) { Text("Готово", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
