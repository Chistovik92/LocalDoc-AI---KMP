@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.net.DownloadSpeed
import com.localdoc.ai.novel.AutoUpdateService
import com.localdoc.ai.novel.AutoUpdateSettings
import com.localdoc.ai.novel.TrackedNovel
import kotlinx.coroutines.launch

private val DAY_LABELS = listOf(1 to "Пн", 2 to "Вт", 3 to "Ср", 4 to "Чт", 5 to "Пт", 6 to "Сб", 7 to "Вс")

/** Раздел «Скачивание и обновления»: скорость скачивания и автоматическая проверка новых глав скачанных новелл. */
@Composable
fun UpdatesSection() {
    SpeedCard()
    AutoUpdateCard()
    TrackedNovelsCard()
}

@Composable
private fun SpeedCard() {
    val speed by AppGraph.downloadSpeed.value.collectAsState()
    SectionCard("Скорость скачивания") {
        Text(
            "Сколько приложение ждёт между запросами к одному сайту. Чем быстрее, тем выше риск, что сайт примет скачивание за атаку " +
                "и ограничит доступ; если сайт указал паузу в robots.txt (Crawl-delay), она соблюдается в любом режиме.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DownloadSpeed.entries.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { AppGraph.downloadSpeed.update { option } }
            ) {
                RadioButton(selected = speed == option, onClick = { AppGraph.downloadSpeed.update { option } })
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                    Text(option.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AutoUpdateCard() {
    val s by AppGraph.autoUpdateSettings.value.collectAsState()
    fun change(transform: (AutoUpdateSettings) -> AutoUpdateSettings) = AppGraph.updateAutoUpdate(transform)

    SectionCard("Автообновление новелл") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Проверять новые главы", style = MaterialTheme.typography.titleSmall)
                Text(
                    "В фоне, по расписанию. Система может сдвинуть время запуска, чтобы беречь батарею. Приложение только сообщает о новых главах — скачивание запускаете вы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = s.enabled, onCheckedChange = { on -> change { it.copy(enabled = on) } })
        }
        if (!s.enabled) return@SectionCard

        Text("Как часто", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoUpdateSettings.INTERVAL_CHOICES.forEach { hours ->
                FilterChip(
                    selected = s.intervalHours == hours,
                    onClick = { change { it.copy(intervalHours = hours) } },
                    label = { Text(intervalLabel(hours), maxLines = 1, softWrap = false) }
                )
            }
        }

        Text("В какие дни", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DAY_LABELS.forEach { (day, label) ->
                val on = s.days.isEmpty() || day in s.days
                FilterChip(
                    selected = on,
                    onClick = {
                        change {
                            val current = if (it.days.isEmpty()) DAY_LABELS.map { d -> d.first }.toSet() else it.days
                            val next = if (day in current) current - day else current + day
                            // Хотя бы один день должен остаться, иначе расписание никогда не сработает
                            it.copy(days = if (next.isEmpty()) current else next)
                        }
                    },
                    label = { Text(label, maxLines = 1, softWrap = false) }
                )
            }
        }

        SwitchRow("Тихие часы", "В это время проверки не запускаются", s.quietEnabled) { on -> change { it.copy(quietEnabled = on) } }
        if (s.quietEnabled) {
            HourRow("С", s.quietFromMinute / 60) { h -> change { it.copy(quietFromMinute = h * 60) } }
            HourRow("До", s.quietToMinute / 60) { h -> change { it.copy(quietToMinute = h * 60) } }
        }
        SwitchRow("Только по Wi-Fi", "На мобильном интернете проверки не запускаются", s.wifiOnly) { on -> change { it.copy(wifiOnly = on) } }
    }
}

@Composable
private fun TrackedNovelsCard() {
    val novels by AppGraph.novelTracker.all.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    SectionCard("Следим за новеллами") {
        if (novels.isEmpty()) {
            Text(
                "Здесь появятся новеллы, скачанные целиком по оглавлению («Скачать книгу» на странице новеллы).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        novels.forEach { novel -> TrackedNovelRow(novel) }
        Button(
            enabled = !running,
            onClick = {
                running = true
                result = null
                scope.launch {
                    result = try {
                        val report = AppGraph.novelChecker.checkAll()
                        when {
                            report.throttled -> "Сайт просит подождать — повторите позже"
                            report.updates.isNotEmpty() -> AutoUpdateService.noticeText(report.updates)
                            report.skippedByRobots.isNotEmpty() -> "Сайт не разрешает автоматическую проверку (robots.txt)"
                            report.failed > 0 -> "Не удалось проверить: ${report.failed}"
                            else -> "Новых глав нет"
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: "Не удалось проверить"
                    }
                    running = false
                }
            }
        ) { Text(if (running) "Проверяем…" else "Проверить сейчас", maxLines = 1, softWrap = false) }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun TrackedNovelRow(novel: TrackedNovel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(novel.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val status = when {
                novel.lastError != null -> "Ошибка: ${novel.lastError}"
                novel.pending.isNotEmpty() -> "Новых глав: ${novel.pending.size} — скачайте книгу ещё раз, и они добавятся"
                novel.lastCheckedMs == 0L -> "Ещё не проверялась"
                else -> "Новых глав нет"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = novel.autoCheck, onCheckedChange = { AppGraph.novelTracker.setAutoCheck(novel.id, it) })
        TextButton(onClick = { AppGraph.novelTracker.remove(novel.id) }) { Text("Убрать", maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HourRow(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((hour + 23) % 24) }) { Text("−", maxLines = 1, softWrap = false) }
        Text("${hour.toString().padStart(2, '0')}:00", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { onChange((hour + 1) % 24) }) { Text("+", maxLines = 1, softWrap = false) }
    }
}

private fun intervalLabel(hours: Int): String = when {
    hours < 24 -> "$hours ч"
    hours == 24 -> "Раз в сутки"
    hours == 72 -> "Раз в 3 дня"
    hours == 168 -> "Раз в неделю"
    else -> "$hours ч"
}
