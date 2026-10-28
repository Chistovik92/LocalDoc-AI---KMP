package com.localdoc.ai.ui.reader

import com.localdoc.ai.resources.battery_use_the_microphone_and_recognition
import com.localdoc.ai.resources.next_back_pause_continue_next_paragraph
import com.localdoc.ai.resources.voice_commands
import com.localdoc.ai.resources.voice_control
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph

/** Переключатель голосового управления чтением в настройках озвучки. Выключен по умолчанию. */
@Composable
internal fun VoiceControlSection() {
    val settings by AppGraph.voiceControlSettings.value.collectAsState()
    val status by AppGraph.voiceControl.status.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.voice_control))
        SwitchLine(
            stringResource(Res.string.voice_commands),
            stringResource(Res.string.next_back_pause_continue_next_paragraph),
            settings.enabled
        ) { on ->
            if (on) AppGraph.voiceControl.enable { } else AppGraph.voiceControl.disable()
        }
        Text(
            stringResource(Res.string.battery_use_the_microphone_and_recognition),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
