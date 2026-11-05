package com.localdoc.ai.platform

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun AudioRouteButton(modifier: Modifier) {
    IconButton(onClick = { openAudioOutputPicker(AppContextHolder.appContext) }, modifier = modifier) {
        Icon(Icons.Default.Cast, contentDescription = "Куда играть: колонка, наушники, Cast")
    }
}

/** Панель вывода звука Android 10+; если её нет у производителя — настройки Cast, затем Bluetooth. */
private fun openAudioOutputPicker(context: Context) {
    val attempts = listOf(
        Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName),
        Intent(Settings.ACTION_CAST_SETTINGS),
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    )
    for (intent in attempts) {
        val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (started) return
    }
}
