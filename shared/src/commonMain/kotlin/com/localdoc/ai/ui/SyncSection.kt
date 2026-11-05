package com.localdoc.ai.ui

import com.localdoc.ai.resources.a_backup_is_saved_on_the
import com.localdoc.ai.resources.books_pending
import com.localdoc.ai.resources.done_received_sent_conflicts
import com.localdoc.ai.resources.encryption_password
import com.localdoc.ai.resources.error_2
import com.localdoc.ai.resources.every_days
import com.localdoc.ai.resources.icloud_and_google_drive_not_available
import com.localdoc.ai.resources.keep_this_device_s_data
import com.localdoc.ai.resources.last_backups_kept_backups_are_stored
import com.localdoc.ai.resources.login
import com.localdoc.ai.resources.make_a_backup_automatically
import com.localdoc.ai.resources.merge_anyway
import com.localdoc.ai.resources.not_applied
import com.localdoc.ai.resources.reading_progress_bookmarks_quotes_and_collec
import com.localdoc.ai.resources.scheduled_backup
import com.localdoc.ai.resources.server_password
import com.localdoc.ai.resources.sync_2
import com.localdoc.ai.resources.sync_stopped
import com.localdoc.ai.resources.syncing
import com.localdoc.ai.resources.take_the_data_from_the_cloud
import com.localdoc.ai.resources.the_file_in_the_cloud_was
import com.localdoc.ai.resources.webdav_folder_address
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.localdoc.ai.backup.BackupScheduleSettings
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.net.asTransport
import com.localdoc.ai.platform.IoDispatcher
import com.localdoc.ai.platform.RawHttp
import com.localdoc.ai.sync.DivergenceChoice
import com.localdoc.ai.sync.PasswordCipher
import com.localdoc.ai.sync.SyncOutcome
import com.localdoc.ai.sync.WebDavTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY_SYNC_URL = "sync_webdav_url"
private const val KEY_SYNC_USER = "sync_webdav_user"
private const val SECRET_WEBDAV = "sync_webdav_password"
private const val SECRET_ENCRYPTION = "sync_encryption_password"

/**
 * Раздел «Синхронизация»: прогресс, закладки, цитаты и подборки через WebDAV, зашифрованно на устройстве.
 * Пароли сайтов не синхронизируются. iCloud и Google Drive пока недоступны (заготовки платформ).
 */
@Composable
fun SyncSection() {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(AppGraph.kv.get(KEY_SYNC_URL).orEmpty()) }
    var user by remember { mutableStateOf(AppGraph.kv.get(KEY_SYNC_USER).orEmpty()) }
    var password by remember { mutableStateOf(AppGraph.secure.get(SECRET_WEBDAV).orEmpty()) }
    var encryption by remember { mutableStateOf(AppGraph.secure.get(SECRET_ENCRYPTION).orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var diverged by remember { mutableStateOf<String?>(null) }

    fun run(choice: DivergenceChoice?) {
        AppGraph.kv.put(KEY_SYNC_URL, url.trim())
        AppGraph.kv.put(KEY_SYNC_USER, user.trim())
        AppGraph.secure.put(SECRET_WEBDAV, password)
        AppGraph.secure.put(SECRET_ENCRYPTION, encryption)
        busy = true
        message = null
        diverged = null
        scope.launch {
            val transport = WebDavTransport(RawHttp().asTransport(), url.trim(), user.trim(), password)
            val outcome = try {
                withContext(IoDispatcher) { AppGraph.syncEngine.sync(transport, PasswordCipher(encryption), choice) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncOutcome.Failed(e.message ?: getString(Res.string.error_2))
            }
            busy = false
            when (outcome) {
                is SyncOutcome.Done ->
                    message = getString(Res.string.done_received_sent_conflicts, outcome.pulled, outcome.pushed, outcome.conflicts) +
                        (if (outcome.deferred > 0) getString(Res.string.books_pending, outcome.deferred) else "") +
                        (if (outcome.failedKinds.isNotEmpty()) getString(Res.string.not_applied, outcome.failedKinds.joinToString()) else "")
                is SyncOutcome.Diverged -> diverged = outcome.message
                is SyncOutcome.WrongPassword -> message = outcome.message
                is SyncOutcome.TooNew -> message = getString(Res.string.the_file_in_the_cloud_was)
                is SyncOutcome.Failed -> message = outcome.message
            }
        }
    }

    Text(
        stringResource(Res.string.reading_progress_bookmarks_quotes_and_collec),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(url, { url = it }, label = { Text(stringResource(Res.string.webdav_folder_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(user, { user = it }, label = { Text(stringResource(Res.string.login)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        password, { password = it }, label = { Text(stringResource(Res.string.server_password)) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        encryption, { encryption = it }, label = { Text(stringResource(Res.string.encryption_password)) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
    )
    Button(
        enabled = !busy && url.isNotBlank() && encryption.isNotEmpty(),
        onClick = { run(null) },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (busy) stringResource(Res.string.syncing) else stringResource(Res.string.sync_2)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    diverged?.let {
        Text(stringResource(Res.string.sync_stopped, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Text(stringResource(Res.string.a_backup_is_saved_on_the), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { run(DivergenceChoice.MERGE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.merge_anyway)) }
        OutlinedButton(onClick = { run(DivergenceChoice.KEEP_LOCAL) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.keep_this_device_s_data)) }
        OutlinedButton(onClick = { run(DivergenceChoice.USE_REMOTE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.take_the_data_from_the_cloud)) }
    }
    Text(stringResource(Res.string.icloud_and_google_drive_not_available), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    ScheduledBackupCard()
}

/** Копия по расписанию: проверяется при запуске приложения, хранится в памяти приложения. */
@Composable
private fun ScheduledBackupCard() {
    var settings by remember { mutableStateOf(AppGraph.scheduledBackup.settings()) }
    fun change(next: BackupScheduleSettings) {
        AppGraph.scheduledBackup.update(next)
        settings = AppGraph.scheduledBackup.settings()
    }
    Text(stringResource(Res.string.scheduled_backup), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.make_a_backup_automatically), modifier = Modifier.weight(1f))
        Switch(checked = settings.enabled, onCheckedChange = { change(settings.copy(enabled = it)) })
    }
    if (settings.enabled) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.every_days, settings.everyDays), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { change(settings.copy(everyDays = (settings.everyDays - 1).coerceAtLeast(1))) }) { Text("−") }
            OutlinedButton(onClick = { change(settings.copy(everyDays = settings.everyDays + 1)) }) { Text("+") }
        }
        Text(
            stringResource(Res.string.last_backups_kept_backups_are_stored, settings.keep),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
