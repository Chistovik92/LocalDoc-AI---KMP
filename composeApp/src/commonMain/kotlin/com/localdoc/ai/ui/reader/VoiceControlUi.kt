package com.localdoc.ai.ui.reader

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
        SectionLabel("Голосовое управление")
        SwitchLine(
            "Команды голосом",
            "«Дальше», «назад», «пауза», «продолжай», «абзац дальше». Микрофон слушает только пока открыт плеер озвучки; " +
                "распознавание идёт на устройстве, звук никуда не отправляется. Работает лучше в наушниках: " +
                "микрофон может слышать саму озвучку.",
            settings.enabled
        ) { on ->
            if (on) AppGraph.voiceControl.enable { } else AppGraph.voiceControl.disable()
        }
        Text(
            "Расход батареи: микрофон и распознавание работают постоянно, пока звучит книга, — ориентировочно на 5–10 % " +
                "заряда в час сверх самой озвучки; зависит от устройства. Вне плеера расход нулевой.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
