package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.platform.AppLocale
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.font_size_follows_the_system
import com.localdoc.ai.resources.high_contrast_hint
import com.localdoc.ai.resources.high_contrast_theme
import com.localdoc.ai.resources.interface_language
import com.localdoc.ai.resources.keyboard_shortcuts_hint
import com.localdoc.ai.resources.keyboard_shortcuts_title
import com.localdoc.ai.resources.language_applies_after_restart
import com.localdoc.ai.resources.language_applies_now
import com.localdoc.ai.resources.language_system
import com.localdoc.ai.resources.motion_title
import com.localdoc.ai.resources.reduce_motion
import com.localdoc.ai.resources.reduce_motion_hint
import org.jetbrains.compose.resources.stringResource

/** Раздел «Интерфейс и доступность»: язык, контраст, анимации, подсказки по шрифту и клавиатуре. */
@Composable
fun InterfaceSection() {
    var language by remember { mutableStateOf(AppLanguage.fromTag(AppLocale.savedTag())) }
    val accessibility by AppGraph.accessibilitySettings.value.collectAsState()

    SectionTitle(stringResource(Res.string.interface_language))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        AppLanguage.entries.forEach { option ->
            val name = option.nativeName ?: stringResource(Res.string.language_system)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = language == option, role = Role.RadioButton) {
                    language = option
                    AppLocale.save(option.tag)
                }
            ) {
                RadioButton(selected = language == option, onClick = null, modifier = Modifier.padding(12.dp))
                Text(name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Text(
        stringResource(if (AppLocale.appliesImmediately) Res.string.language_applies_now else Res.string.language_applies_after_restart),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SectionTitle(stringResource(Res.string.motion_title))
    ToggleRow(stringResource(Res.string.high_contrast_theme), stringResource(Res.string.high_contrast_hint), accessibility.highContrast) { on ->
        AppGraph.accessibilitySettings.update { it.copy(highContrast = on) }
    }
    ToggleRow(stringResource(Res.string.reduce_motion), stringResource(Res.string.reduce_motion_hint), accessibility.reduceMotion) { on ->
        AppGraph.accessibilitySettings.update { it.copy(reduceMotion = on) }
    }

    Text(
        stringResource(Res.string.font_size_follows_the_system),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SectionTitle(stringResource(Res.string.keyboard_shortcuts_title))
    Text(stringResource(Res.string.keyboard_shortcuts_hint), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
