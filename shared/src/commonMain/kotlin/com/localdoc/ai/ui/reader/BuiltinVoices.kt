package com.localdoc.ai.ui.reader

import androidx.compose.ui.semantics.Role
import com.localdoc.ai.resources.built_in_voices
import com.localdoc.ai.resources.cancel_download
import com.localdoc.ai.resources.delete_voice
import com.localdoc.ai.resources.download_voice
import com.localdoc.ai.resources.installed_offline
import com.localdoc.ai.resources.listen_to_voice_sample
import com.localdoc.ai.resources.mb
import com.localdoc.ai.resources.of_mb_2
import com.localdoc.ai.resources.piper_neural_voices_work_right_inside
import com.localdoc.ai.resources.unpacking
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.LanguageVoice
import com.localdoc.ai.lang.Languages
import com.localdoc.ai.tts.PiperSpeechEngine
import com.localdoc.ai.tts.PiperVoice
import com.localdoc.ai.tts.PiperVoiceCatalog
import com.localdoc.ai.tts.VoicePackState
import kotlinx.coroutines.launch

/**
 * Раздел «Встроенные голоса»: открытые нейросетевые голоса Piper, которые
 * скачиваются в приложение и читают без интернета и сторонних TTS-движков.
 */
@Composable
internal fun BuiltinVoicesSection() {
    val voice by AppGraph.voiceSettings.value.collectAsState()
    val states by AppGraph.voicePacks.states.collectAsState()
    val scope = rememberCoroutineScope()
    val selectedId = voice.voiceId.takeIf { voice.engineId == PiperSpeechEngine.ENGINE_ID }

    LaunchedEffect(Unit) { AppGraph.voicePacks.refresh() }

    SectionLabel(stringResource(Res.string.built_in_voices))
    Text(
        stringResource(Res.string.piper_neural_voices_work_right_inside),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            PiperVoiceCatalog.voices.forEach { pack ->
                BuiltinVoiceRow(
                    pack = pack,
                    state = states[pack.id] ?: VoicePackState.NotInstalled,
                    selected = selectedId == pack.id,
                    onSelect = { select(pack) },
                    onDownload = { AppGraph.voicePacks.download(pack) },
                    onCancel = { AppGraph.voicePacks.cancel(pack) },
                    onListen = { AppGraph.voiceSamplePlayer.play(pack.sampleUrl) { } },
                    onDelete = {
                        scope.launch {
                            if (selectedId == pack.id) {
                                AppGraph.readAloud.pause()
                                AppGraph.updateVoice { it.copy(engineId = null, voiceId = null) }
                            }
                            AppGraph.speaker.builtin.unload()
                            AppGraph.voicePacks.delete(pack)
                        }
                    }
                )
            }
        }
    }
}

private fun select(pack: PiperVoice) {
    AppGraph.updateVoice { settings ->
        val code = Languages.baseCode(pack.language)
        val choice = LanguageVoice(PiperSpeechEngine.ENGINE_ID, pack.id)
        settings.copy(
            engineId = choice.engineId,
            voiceId = choice.voiceId,
            perLanguage = if (code != null) settings.perLanguage + (code to choice) else settings.perLanguage
        )
    }
    // Пробная фраза не должна сбивать идущую озвучку книги
    if (!AppGraph.readAloud.state.value.playing) {
        val sample = if (pack.language.startsWith("ru")) "Так звучит выбранный голос." else "This is how the selected voice sounds."
        AppGraph.speaker.speak(sample) { }
    }
}

@Composable
private fun BuiltinVoiceRow(
    pack: PiperVoice,
    state: VoicePackState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onListen: () -> Unit,
    onDelete: () -> Unit
) {
    val installed = state == VoicePackState.Installed
    ListItem(
        headlineContent = { Text("${pack.name} · ${pack.language}") },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pack.description)
                VoicePackStatus(pack, state)
            }
        },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect, enabled = installed) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onListen) { Icon(Icons.Default.PlayArrow, contentDescription = stringResource(Res.string.listen_to_voice_sample)) }
                when (state) {
                    VoicePackState.Installed -> IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.delete_voice)) }
                    VoicePackState.Installing -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    is VoicePackState.Downloading -> IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cancel_download)) }
                    VoicePackState.NotInstalled, is VoicePackState.Failed ->
                        IconButton(onClick = onDownload) { Icon(Icons.Default.Download, contentDescription = stringResource(Res.string.download_voice)) }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clickable(role = Role.Button, enabled = installed, onClick = onSelect)
    )
}

@Composable
private fun VoicePackStatus(pack: PiperVoice, state: VoicePackState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (state) {
        VoicePackState.NotInstalled -> Text(stringResource(Res.string.mb, pack.sizeMb), color = muted)
        VoicePackState.Installed -> Text(stringResource(Res.string.installed_offline), color = MaterialTheme.colorScheme.primary)
        VoicePackState.Installing -> Text(stringResource(Res.string.unpacking), color = muted)
        is VoicePackState.Downloading -> {
            val total = state.totalBytes.takeIf { it > 0 } ?: pack.sizeBytes
            val progress = (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(stringResource(Res.string.of_mb_2, state.downloadedBytes / MB, total / MB), color = muted)
        }
        is VoicePackState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
    }
}

private const val MB = 1024L * 1024L
