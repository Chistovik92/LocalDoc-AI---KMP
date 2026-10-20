package com.localdoc.ai.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

actual object PlatformActions {

    private val context get() = AppContextHolder.appContext

    actual val appVersion: String
        get() = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        }.getOrNull() ?: "0"

    actual val supportsTtsEngines: Boolean = true

    actual fun openUrl(url: String) {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    actual fun openTtsSettings() {
        if (!start(Intent("com.android.settings.TTS_SETTINGS"))) {
            start(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    actual fun installTtsData() {
        start(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
    }

    actual fun installApk(path: String): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val opened = start(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return if (opened) InstallResult.NeedsPermission else InstallResult.Unsupported
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(path))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (start(intent)) InstallResult.Started else InstallResult.Unsupported
    }

    actual suspend fun httpGet(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
