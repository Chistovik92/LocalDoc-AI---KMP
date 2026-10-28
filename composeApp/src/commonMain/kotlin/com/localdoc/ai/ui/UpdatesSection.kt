@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui

import com.localdoc.ai.resources.auto_update_of_novels
import com.localdoc.ai.resources.check_for_new_chapters
import com.localdoc.ai.resources.check_now
import com.localdoc.ai.resources.checking
import com.localdoc.ai.resources.checks_do_not_run_during_this
import com.localdoc.ai.resources.checks_do_not_run_on_mobile
import com.localdoc.ai.resources.could_not_check
import com.localdoc.ai.resources.could_not_check_2
import com.localdoc.ai.resources.download_speed
import com.localdoc.ai.resources.error
import com.localdoc.ai.resources.every_3_days
import com.localdoc.ai.resources.fri
import com.localdoc.ai.resources.from
import com.localdoc.ai.resources.h
import com.localdoc.ai.resources.how_long_the_app_waits_between
import com.localdoc.ai.resources.how_often
import com.localdoc.ai.resources.in_the_background_on_a_schedule
import com.localdoc.ai.resources.mon
import com.localdoc.ai.resources.new_chapters_download_the_book_again
import com.localdoc.ai.resources.no_new_chapters
import com.localdoc.ai.resources.not_checked_yet
import com.localdoc.ai.resources.novels_downloaded_in_full_via_the
import com.localdoc.ai.resources.on_which_days
import com.localdoc.ai.resources.once_a_day
import com.localdoc.ai.resources.once_a_week
import com.localdoc.ai.resources.quiet_hours
import com.localdoc.ai.resources.remove
import com.localdoc.ai.resources.sat
import com.localdoc.ai.resources.sun
import com.localdoc.ai.resources.the_site_asks_to_wait_try
import com.localdoc.ai.resources.the_site_does_not_allow_automatic
import com.localdoc.ai.resources.thu
import com.localdoc.ai.resources.to_text
import com.localdoc.ai.resources.tracking_novels
import com.localdoc.ai.resources.tue
import com.localdoc.ai.resources.wed
import com.localdoc.ai.resources.wi_fi_only
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.speed_cautious
import com.localdoc.ai.resources.speed_cautious_hint
import com.localdoc.ai.resources.speed_fast
import com.localdoc.ai.resources.speed_fast_hint
import com.localdoc.ai.resources.speed_normal
import com.localdoc.ai.resources.speed_normal_hint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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

private val DAY_LABELS = listOf(1 to Res.string.mon, 2 to Res.string.tue, 3 to Res.string.wed, 4 to Res.string.thu, 5 to Res.string.fri, 6 to Res.string.sat, 7 to Res.string.sun)

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
    SectionCard(stringResource(Res.string.download_speed)) {
        Text(
            stringResource(Res.string.how_long_the_app_waits_between),
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
                    Text(stringResource(speedLabel(option)), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(speedHint(option)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AutoUpdateCard() {
    val s by AppGraph.autoUpdateSettings.value.collectAsState()
    fun change(transform: (AutoUpdateSettings) -> AutoUpdateSettings) = AppGraph.updateAutoUpdate(transform)

    SectionCard(stringResource(Res.string.auto_update_of_novels)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.check_for_new_chapters), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(Res.string.in_the_background_on_a_schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = s.enabled, onCheckedChange = { on -> change { it.copy(enabled = on) } })
        }
        if (!s.enabled) return@SectionCard

        Text(stringResource(Res.string.how_often), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoUpdateSettings.INTERVAL_CHOICES.forEach { hours ->
                FilterChip(
                    selected = s.intervalHours == hours,
                    onClick = { change { it.copy(intervalHours = hours) } },
                    label = { Text(intervalLabel(hours), maxLines = 1, softWrap = false) }
                )
            }
        }

        Text(stringResource(Res.string.on_which_days), style = MaterialTheme.typography.titleSmall)
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
                    label = { Text(stringResource(label), maxLines = 1, softWrap = false) }
                )
            }
        }

        SwitchRow(stringResource(Res.string.quiet_hours), stringResource(Res.string.checks_do_not_run_during_this), s.quietEnabled) { on -> change { it.copy(quietEnabled = on) } }
        if (s.quietEnabled) {
            HourRow(stringResource(Res.string.from), s.quietFromMinute / 60) { h -> change { it.copy(quietFromMinute = h * 60) } }
            HourRow(stringResource(Res.string.to_text), s.quietToMinute / 60) { h -> change { it.copy(quietToMinute = h * 60) } }
        }
        SwitchRow(stringResource(Res.string.wi_fi_only), stringResource(Res.string.checks_do_not_run_on_mobile), s.wifiOnly) { on -> change { it.copy(wifiOnly = on) } }
    }
}

@Composable
private fun TrackedNovelsCard() {
    val novels by AppGraph.novelTracker.all.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    SectionCard(stringResource(Res.string.tracking_novels)) {
        if (novels.isEmpty()) {
            Text(
                stringResource(Res.string.novels_downloaded_in_full_via_the),
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
                            report.throttled -> getString(Res.string.the_site_asks_to_wait_try)
                            report.updates.isNotEmpty() -> AutoUpdateService.noticeText(report.updates)
                            report.skippedByRobots.isNotEmpty() -> getString(Res.string.the_site_does_not_allow_automatic)
                            report.failed > 0 -> getString(Res.string.could_not_check, report.failed)
                            else -> getString(Res.string.no_new_chapters)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: getString(Res.string.could_not_check_2)
                    }
                    running = false
                }
            }
        ) { Text(if (running) stringResource(Res.string.checking) else stringResource(Res.string.check_now), maxLines = 1, softWrap = false) }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun TrackedNovelRow(novel: TrackedNovel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(novel.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val status = when {
                novel.lastError != null -> stringResource(Res.string.error, novel.lastError)
                novel.pending.isNotEmpty() -> stringResource(Res.string.new_chapters_download_the_book_again, novel.pending.size)
                novel.lastCheckedMs == 0L -> stringResource(Res.string.not_checked_yet)
                else -> stringResource(Res.string.no_new_chapters)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = novel.autoCheck, onCheckedChange = { AppGraph.novelTracker.setAutoCheck(novel.id, it) })
        TextButton(onClick = { AppGraph.novelTracker.remove(novel.id) }) { Text(stringResource(Res.string.remove), maxLines = 1, softWrap = false) }
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

@Composable
private fun intervalLabel(hours: Int): String = when {
    hours < 24 -> stringResource(Res.string.h, hours)
    hours == 24 -> stringResource(Res.string.once_a_day)
    hours == 72 -> stringResource(Res.string.every_3_days)
    hours == 168 -> stringResource(Res.string.once_a_week)
    else -> stringResource(Res.string.h, hours)
}

private fun speedLabel(speed: DownloadSpeed): StringResource = when (speed) {
    DownloadSpeed.CAUTIOUS -> Res.string.speed_cautious
    DownloadSpeed.NORMAL -> Res.string.speed_normal
    DownloadSpeed.FAST -> Res.string.speed_fast
}

private fun speedHint(speed: DownloadSpeed): StringResource = when (speed) {
    DownloadSpeed.CAUTIOUS -> Res.string.speed_cautious_hint
    DownloadSpeed.NORMAL -> Res.string.speed_normal_hint
    DownloadSpeed.FAST -> Res.string.speed_fast_hint
}
