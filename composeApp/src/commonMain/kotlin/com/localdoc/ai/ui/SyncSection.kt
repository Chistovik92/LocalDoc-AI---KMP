package com.localdoc.ai.ui

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
                SyncOutcome.Failed(e.message ?: "ошибка")
            }
            busy = false
            when (outcome) {
                is SyncOutcome.Done ->
                    message = "Готово: получено ${outcome.pulled}, отправлено ${outcome.pushed}, конфликтов ${outcome.conflicts}" +
                        (if (outcome.deferred > 0) ", ждут книги: ${outcome.deferred}" else "") +
                        (if (outcome.failedKinds.isNotEmpty()) ". Не применено: ${outcome.failedKinds.joinToString()}" else "")
                is SyncOutcome.Diverged -> diverged = outcome.message
                is SyncOutcome.WrongPassword -> message = outcome.message
                is SyncOutcome.TooNew -> message = "Файл в облаке создан более новой версией LocalDoc. Обновите приложение."
                is SyncOutcome.Failed -> message = outcome.message
            }
        }
    }

    Text(
        "Прогресс чтения, закладки, цитаты и подборки сохраняются в одном файле на вашем WebDAV-сервере (Nextcloud, ownCloud и др.), " +
            "зашифрованном паролем на устройстве. Пароль шифрования нужно ввести одинаковым на всех устройствах — " +
            "его нельзя восстановить. Пароли сайтов не синхронизируются.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(url, { url = it }, label = { Text("Адрес папки WebDAV") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        password, { password = it }, label = { Text("Пароль сервера") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        encryption, { encryption = it }, label = { Text("Пароль шифрования") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
    )
    Button(
        enabled = !busy && url.isNotBlank() && encryption.isNotEmpty(),
        onClick = { run(null) },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (busy) "Синхронизируем…" else "Синхронизировать") }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    diverged?.let {
        Text("Синхронизация остановлена: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Text("Перед любым выбором на устройстве сохраняется резервная копия.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { run(DivergenceChoice.MERGE) }, modifier = Modifier.fillMaxWidth()) { Text("Всё равно слить") }
        OutlinedButton(onClick = { run(DivergenceChoice.KEEP_LOCAL) }, modifier = Modifier.fillMaxWidth()) { Text("Оставить данные этого устройства") }
        OutlinedButton(onClick = { run(DivergenceChoice.USE_REMOTE) }, modifier = Modifier.fillMaxWidth()) { Text("Взять данные из облака") }
    }
    Text("iCloud и Google Drive: пока недоступны.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
    Text("Копия по расписанию", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Делать копию автоматически", modifier = Modifier.weight(1f))
        Switch(checked = settings.enabled, onCheckedChange = { change(settings.copy(enabled = it)) })
    }
    if (settings.enabled) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Раз в ${settings.everyDays} дн.", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { change(settings.copy(everyDays = (settings.everyDays - 1).coerceAtLeast(1))) }) { Text("−") }
            OutlinedButton(onClick = { change(settings.copy(everyDays = settings.everyDays + 1)) }) { Text("+") }
        }
        Text(
            "Хранится последних копий: ${settings.keep}. Копии лежат в памяти приложения и пропадают при его удалении — " +
                "для надёжности иногда сохраняйте копию файлом в разделе «Резервная копия».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
