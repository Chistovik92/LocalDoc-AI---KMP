package com.localdoc.ai.update

import com.localdoc.ai.platform.PlatformActions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch

/** Доступное обновление из GitHub Releases. */
data class AppUpdate(
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    /** Прямая ссылка на APK (Android) или null — тогда открывается страница релиза. */
    val downloadUrl: String?
)

/**
 * Проверка обновлений: последний релиз репозитория на GitHub сравнивается с версией
 * приложения. Скачивание APK открывается в браузере — система сама предложит установку
 * поверх текущей версии (подпись постоянным ключом совпадает).
 */
class UpdateChecker(
    private val repo: String = "Chistovik92/LocalDoc-AI---KMP",
    private val fetch: suspend (String, Map<String, String>) -> String = { url, headers -> PlatformActions.httpGet(url, headers) }
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList()
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String
    )

    /** null — обновлений нет. Ошибки сети пробрасываются вызывающему. */
    suspend fun check(currentVersion: String, allowApk: Boolean): AppUpdate? {
        val raw = fetch(
            "https://api.github.com/repos/$repo/releases/latest",
            mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "LocalDoc-AI")
        )
        val release = json.decodeFromString(Release.serializer(), raw)
        if (release.draft || release.prerelease) return null
        val latest = release.tagName.removePrefix("v")
        if (compareVersions(latest, currentVersion) <= 0) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("debug", ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk") }
        return AppUpdate(
            version = latest,
            title = release.name?.takeIf { it.isNotBlank() } ?: "LocalDoc AI $latest",
            notes = release.body.orEmpty().trim(),
            pageUrl = release.htmlUrl,
            downloadUrl = if (allowApk) apk?.downloadUrl else null
        )
    }

    companion object {
        /** Сравнение версий вида 1.5.0 / 1.10.2 / 1.5.0-beta (суффикс игнорируется). */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }
    }
}

/** Состояние проверки обновлений для UI (баннер на главной и раздел «О приложении»). */
class UpdateManager(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val checker: UpdateChecker = UpdateChecker()
) {
    private val _update = kotlinx.coroutines.flow.MutableStateFlow<AppUpdate?>(null)
    val update: kotlinx.coroutines.flow.StateFlow<AppUpdate?> = _update

    private val _status = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Результат ручной проверки: «Установлена последняя версия» / ошибка. */
    val status: kotlinx.coroutines.flow.StateFlow<String?> = _status

    private val _checking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val checking: kotlinx.coroutines.flow.StateFlow<Boolean> = _checking

    /** silent = true — фоновая проверка при запуске: ошибки сети не показываются. */
    fun check(silent: Boolean) {
        if (_checking.value) return
        _checking.value = true
        scope.launch {
            try {
                val found = checker.check(PlatformActions.appVersion, allowApk = PlatformActions.supportsTtsEngines)
                _update.value = found
                if (!silent) _status.value = if (found == null) "Установлена последняя версия ${PlatformActions.appVersion}" else null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (!silent) _status.value = "Не удалось проверить обновления: ${t.message}"
            } finally {
                _checking.value = false
            }
        }
    }

    fun dismiss() {
        _update.value = null
    }

    fun install(update: AppUpdate) {
        PlatformActions.openUrl(update.downloadUrl ?: update.pageUrl)
    }
}
