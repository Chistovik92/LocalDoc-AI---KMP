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

/** Раздел «Сайты и вход»: сохранённые входы с кнопкой «Забыть сайт» (пароль и сессия стираются целиком). */
@Composable
fun SitesSection() {
    val sites by AppGraph.siteVault.sites.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AppGraph.siteVault.refresh() }

    Text(
        stringResource(Res.string.sign_ins_to_web_novel_and),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
