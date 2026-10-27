package com.localdoc.ai.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.localdoc.ai.tts.SleepMode

private val SLEEP_PRESETS = listOf(15, 30, 45, 60, 90)

/** Подпись оставшегося времени: «12 мин», «до конца главы». */
internal fun sleepTimerLabel(mode: SleepMode?, remainingMs: Long): String = when (mode) {
    null -> "Таймер сна"
    SleepMode.ChapterEnd -> "до конца главы"
    is SleepMode.After -> "${((remainingMs + 59_999) / 60_000).coerceAtLeast(1)} мин"
}

/** Кнопка таймера сна в плеере озвучки: открывает выбор времени. */
@Composable
fun SleepTimerButton() {
    val timer = AppGraph.sleepTimer
    val state by timer.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val active = state.mode != null
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Default.Bedtime,
            contentDescription = if (active) "Таймер сна: ${sleepTimerLabel(state.mode, state.remainingMs)}" else "Таймер сна",
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Таймер сна") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (active) {
                    Text(
                        "Озвучка остановится: ${sleepTimerLabel(state.mode, state.remainingMs)}. Перед остановкой голос плавно затихает.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                SLEEP_PRESETS.forEach { minutes ->
                    SleepOption("$minutes минут") { timer.startAfter(minutes); open = false }
                }
                SleepOption("До конца главы") { timer.startAtChapterEnd(); open = false }
                if (state.mode is SleepMode.After) SleepOption("Ещё 10 минут") { timer.extend(10); open = false }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } },
        dismissButton = if (active) ({ TextButton(onClick = { timer.cancel(); open = false }) { Text("Выключить") } }) else null
    )
}

@Composable
private fun SleepOption(text: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
