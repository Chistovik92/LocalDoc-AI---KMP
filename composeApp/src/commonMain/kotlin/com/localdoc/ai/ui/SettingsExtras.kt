package com.localdoc.ai.ui

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

/** Раздел «Сайты и вход»: сохранённые входы с кнопкой «Забыть сайт» (пароль и сессия стираются целиком). */
@Composable
fun SitesSection() {
    val sites by AppGraph.siteVault.sites.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AppGraph.siteVault.refresh() }

    Text(
        "Входы на сайты веб-новелл и книг. Пароли лежат в защищённом хранилище устройства (Android Keystore / iOS Keychain), " +
            "сессии (cookie) зашифрованы, в резервные копии ничего не попадает. Капчу и защиту сайтов приложение не обходит.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (sites.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Lock,
            title = "Входов пока нет",
            text = "Если сайт попросит войти, приложение предложит ввести логин и пароль или войти во встроенном браузере."
        )
    }
    sites.forEach { site ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(site.host, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        site.login + if (site.hasSession) " · сессия активна" else " · нужно войти заново",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = { confirm = site.host }) { Text("Забыть сайт", maxLines = 1, softWrap = false) }
            }
        }
    }
    confirm?.let { host ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Забыть $host?") },
            text = { Text("Будут удалены сохранённые логин и пароль и сессия на этом сайте. Скачанные книги останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.siteAuth.forget(host)
                    clearBrowserCookies(host)
                    confirm = null
                }) { Text("Забыть", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена", maxLines = 1, softWrap = false) } }
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
        "Картинки из интернета кэшируются на диске (не больше 160 МБ, старые удаляются сами). Страницы скачиваемых " +
            "веб-новелл хранятся в отдельной папке приложения, пока вы не соберёте из них книгу; после обрыва загрузка продолжается с этого места.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    StorageRow("Кэш картинок", imageBytes, "Очистить") {
        scope.launch {
            AppGraph.images.clearCache()
            imageBytes = AppGraph.images.cacheBytes()
        }
    }
    StorageRow("Скачанные страницы новелл", novelBytes, "Удалить") {
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
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> {
        val tenths = bytes * 10 / (1024 * 1024)
        "${tenths / 10},${tenths % 10} МБ"
    }
}
