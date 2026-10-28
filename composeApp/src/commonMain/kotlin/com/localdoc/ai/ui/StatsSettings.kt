package com.localdoc.ai.ui

import com.localdoc.ai.resources.cancel
import com.localdoc.ai.resources.h_min
import com.localdoc.ai.resources.last_7_days
import com.localdoc.ai.resources.last_days
import com.localdoc.ai.resources.less_than_a_minute
import com.localdoc.ai.resources.min
import com.localdoc.ai.resources.n_0_min
import com.localdoc.ai.resources.no_data_yet
import com.localdoc.ai.resources.open_a_book_and_read_or
import com.localdoc.ai.resources.reading_and_listening_time_is_counted
import com.localdoc.ai.resources.reset
import com.localdoc.ai.resources.reset_statistics
import com.localdoc.ai.resources.reset_the_statistics
import com.localdoc.ai.resources.speed
import com.localdoc.ai.resources.streak
import com.localdoc.ai.resources.time_speed_and_streak_will_be
import com.localdoc.ai.resources.today
import com.localdoc.ai.resources.total
import com.localdoc.ai.resources.words_2
import com.localdoc.ai.resources.wpm
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import com.localdoc.ai.resources.days_count
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.net.nowMillis
import com.localdoc.ai.platform.localUtcOffsetMillis
import com.localdoc.ai.stats.DateMath
import com.localdoc.ai.stats.ReadingSummary

private const val CHART_DAYS = 14

/** «1 ч 05 мин» / «12 мин» / «меньше минуты». */
@Composable
internal fun formatReadingTime(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> stringResource(Res.string.h_min, minutes / 60, (minutes % 60).toString().padStart(2, '0'))
        minutes >= 1 -> stringResource(Res.string.min, minutes)
        ms > 0 -> stringResource(Res.string.less_than_a_minute)
        else -> stringResource(Res.string.n_0_min)
    }
}

/** Раздел «Статистика чтения»: время, скорость и серия по дням. Данные только на устройстве. */
@Composable
fun StatsSection() {
    val stats by AppGraph.readingStatsSetting.value.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val now = nowMillis()
    val summary = remember(stats) { ReadingSummary.of(stats, DateMath.dayOf(now, localUtcOffsetMillis(now))) }

    Text(
        stringResource(Res.string.reading_and_listening_time_is_counted),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (summary.totalMs == 0L) {
        EmptyState(
            icon = Icons.Default.BarChart,
            title = stringResource(Res.string.no_data_yet),
            text = stringResource(Res.string.open_a_book_and_read_or)
        )
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard(stringResource(Res.string.today), formatReadingTime(summary.todayMs), Modifier.weight(1f))
        StatCard(stringResource(Res.string.last_7_days), formatReadingTime(summary.weekMs), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard(stringResource(Res.string.speed), if (summary.wordsPerMinute > 0) stringResource(Res.string.wpm, summary.wordsPerMinute) else "—", Modifier.weight(1f))
        StatCard(stringResource(Res.string.streak), daysLabel(summary.streakDays), Modifier.weight(1f))
    }
    StatCard(stringResource(Res.string.total), stringResource(Res.string.words_2, formatReadingTime(summary.totalMs), summary.totalWords), Modifier.fillMaxWidth())

    Text(stringResource(Res.string.last_days, CHART_DAYS), style = MaterialTheme.typography.titleSmall)
    DaysChart(summary)

    OutlinedButton(onClick = { confirmReset = true }) { Text(stringResource(Res.string.reset_statistics)) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(Res.string.reset_the_statistics)) },
            text = { Text(stringResource(Res.string.time_speed_and_streak_will_be)) },
            confirmButton = {
                TextButton(onClick = { AppGraph.resetReadingStats(); confirmReset = false }) { Text(stringResource(Res.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.cancel)) } }
        )
    }
}

@Composable
private fun daysLabel(days: Int): String = pluralStringResource(Res.plurals.days_count, days, days)

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Столбики времени по дням; высота — доля от самого читательного дня за период. */
@Composable
private fun DaysChart(summary: ReadingSummary) {
    val days = remember(summary) { summary.lastDays(CHART_DAYS) }
    val max = (days.maxOfOrNull { it.second.ms } ?: 0L).coerceAtLeast(1L)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().height(96.dp)
    ) {
        days.forEach { (_, stats) ->
            val fraction = stats.ms.toFloat() / max
            Box(
                Modifier.weight(1f)
                    .height((8 + 88 * fraction).dp)
                    .background(
                        if (stats.ms > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(days.first().first.drop(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f))
        Text(days.last().first.drop(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
