package com.localdoc.ai.ui.reader

import androidx.compose.ui.semantics.Role
import com.localdoc.ai.resources.close
import com.localdoc.ai.resources.min
import com.localdoc.ai.resources.minutes
import com.localdoc.ai.resources.n_10_more_minutes
import com.localdoc.ai.resources.sleep_timer
import com.localdoc.ai.resources.sleep_timer_2
import com.localdoc.ai.resources.speech_will_stop_the_voice_fades
import com.localdoc.ai.resources.turn_off
import com.localdoc.ai.resources.until_the_end_of_the_chapter
import com.localdoc.ai.resources.until_the_end_of_the_chapter_2
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
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
@Composable
internal fun sleepTimerLabel(mode: SleepMode?, remainingMs: Long): String = when (mode) {
    null -> stringResource(Res.string.sleep_timer)
    SleepMode.ChapterEnd -> stringResource(Res.string.until_the_end_of_the_chapter)
    is SleepMode.After -> stringResource(Res.string.min, ((remainingMs + 59_999) / 60_000).coerceAtLeast(1))
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
            contentDescription = if (active) stringResource(Res.string.sleep_timer_2, sleepTimerLabel(state.mode, state.remainingMs)) else stringResource(Res.string.sleep_timer),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(Res.string.sleep_timer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (active) {
                    Text(
                        stringResource(Res.string.speech_will_stop_the_voice_fades, sleepTimerLabel(state.mode, state.remainingMs)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                SLEEP_PRESETS.forEach { minutes ->
                    SleepOption(stringResource(Res.string.minutes, minutes)) { timer.startAfter(minutes); open = false }
                }
                SleepOption(stringResource(Res.string.until_the_end_of_the_chapter_2)) { timer.startAtChapterEnd(); open = false }
                if (state.mode is SleepMode.After) SleepOption(stringResource(Res.string.n_10_more_minutes)) { timer.extend(10); open = false }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.close)) } },
        dismissButton = if (active) ({ TextButton(onClick = { timer.cancel(); open = false }) { Text(stringResource(Res.string.turn_off)) } }) else null
    )
}

@Composable
private fun SleepOption(text: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
