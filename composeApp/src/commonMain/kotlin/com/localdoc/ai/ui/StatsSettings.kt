package com.localdoc.ai.ui

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
internal fun formatReadingTime(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} ч ${(minutes % 60).toString().padStart(2, '0')} мин"
        minutes >= 1 -> "$minutes мин"
        ms > 0 -> "меньше минуты"
        else -> "0 мин"
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
        "Считается время чтения и озвучки, когда страницы листаются или звучат предложения. Паузы, фон и простой дольше " +
            "трёх минут не засчитываются. Статистика хранится на устройстве и входит в резервную копию.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (summary.totalMs == 0L) {
        EmptyState(
            icon = Icons.Default.BarChart,
            title = "Пока нет данных",
            text = "Откройте книгу и почитайте или послушайте — статистика появится здесь."
        )
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("Сегодня", formatReadingTime(summary.todayMs), Modifier.weight(1f))
        StatCard("За 7 дней", formatReadingTime(summary.weekMs), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("Скорость", if (summary.wordsPerMinute > 0) "${summary.wordsPerMinute} сл/мин" else "—", Modifier.weight(1f))
        StatCard("Серия", daysLabel(summary.streakDays), Modifier.weight(1f))
    }
    StatCard("Всего", "${formatReadingTime(summary.totalMs)} · ${summary.totalWords} слов", Modifier.fillMaxWidth())

    Text("Последние $CHART_DAYS дней", style = MaterialTheme.typography.titleSmall)
    DaysChart(summary)

    OutlinedButton(onClick = { confirmReset = true }) { Text("Сбросить статистику") }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить статистику?") },
            text = { Text("Время, скорость и серия обнулятся. Книги и закладки не затрагиваются.") },
            confirmButton = {
                TextButton(onClick = { AppGraph.resetReadingStats(); confirmReset = false }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } }
        )
    }
}

private fun daysLabel(days: Int): String {
    val word = when {
        days % 100 in 11..14 -> "дней"
        days % 10 == 1 -> "день"
        days % 10 in 2..4 -> "дня"
        else -> "дней"
    }
    return "$days $word"
}

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
