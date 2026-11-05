package com.localdoc.ai.update

import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.download.Downloadable
import com.localdoc.ai.download.ModelDownloader
import com.localdoc.ai.platform.InstallResult
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
    val downloadUrl: String?,
    /** Размер APK в байтах (0 — неизвестен): нужен для прогресса до ответа сервера. */
    val sizeBytes: Long = 0
)

/**
 * Проверка обновлений: последний релиз репозитория на GitHub сравнивается с версией
 * приложения. APK скачивает само приложение ([UpdateManager.install]) и открывает системную
 * установку поверх текущей версии (подпись постоянным ключом совпадает).
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
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long = 0
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
        // Только https: APK из любого другого источника не скачиваем
        val apks = release.assets.filter { it.name.endsWith(".apk") && it.downloadUrl.startsWith("https://") }
        val apk = apks.firstOrNull { !it.name.contains("debug", ignoreCase = true) } ?: apks.firstOrNull()
        return AppUpdate(
            version = latest,
            title = release.name?.takeIf { it.isNotBlank() } ?: "LocalDoc AI $latest",
            notes = releaseNotesToPlain(release.body.orEmpty()),
            pageUrl = release.htmlUrl,
            downloadUrl = if (allowApk) apk?.downloadUrl else null,
            sizeBytes = if (allowApk) apk?.size ?: 0 else 0
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

/** Описание релиза без Markdown-разметки (заголовки, **жирный**, `код`, маркеры списков) для окна обновления. */
fun releaseNotesToPlain(markdown: String): String =
    markdown.lineSequence()
        .map { line ->
            line.trimEnd()
                .replace(Regex("^\\s*#{1,6}\\s*"), "")
                .replace(Regex("^(\\s*)[-*]\\s+")) { it.groupValues[1] + "• " }
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** Ход загрузки APK обновления для UI. */
sealed interface UpdateDownload {
    data object Idle : UpdateDownload

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateDownload {
        /** 0..1 или null, если размер неизвестен. */
        val fraction: Float? get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    data class Failed(val message: String) : UpdateDownload
}

private class UpdateFile(version: String, override val url: String = "", override val sizeBytes: Long = 0) : Downloadable {
    override val id = "localdoc-ai-$version"

    constructor(update: AppUpdate) : this(update.version, update.downloadUrl.orEmpty(), update.sizeBytes)
}

/** Состояние проверки обновлений для UI (баннер на главной и раздел «О приложении»). */
class UpdateManager(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val checker: UpdateChecker = UpdateChecker(),
    private val downloader: ModelDownloader = ModelDownloader("updates", ".apk")
) {
    private val _update = kotlinx.coroutines.flow.MutableStateFlow<AppUpdate?>(null)
    val update: kotlinx.coroutines.flow.StateFlow<AppUpdate?> = _update

    private val _status = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Результат ручной проверки: «Установлена последняя версия» / ошибка. */
    val status: kotlinx.coroutines.flow.StateFlow<String?> = _status

    private val _checking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val checking: kotlinx.coroutines.flow.StateFlow<Boolean> = _checking

    private val _download = kotlinx.coroutines.flow.MutableStateFlow<UpdateDownload>(UpdateDownload.Idle)
    val download: kotlinx.coroutines.flow.StateFlow<UpdateDownload> = _download

    /** silent = true — фоновая проверка при запуске: ошибки сети не показываются. */
    fun check(silent: Boolean) {
        if (_checking.value) return
        _checking.value = true
        scope.launch {
            try {
                val found = checker.check(PlatformActions.appVersion, allowApk = PlatformActions.supportsTtsEngines)
                // APK уже установленной версии больше не нужен — освобождаем место
                downloader.delete(UpdateFile(PlatformActions.appVersion))
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
        cancelDownload()
        _update.value = null
    }

    /**
     * Android: приложение само скачивает APK (с докачкой) и открывает системную установку.
     * Если файл уже скачан (например, после выдачи разрешения на установку), сразу ставит его.
     * Без прямой ссылки на APK (iOS) открывается страница релиза.
     */
    fun install(update: AppUpdate) {
        if (update.downloadUrl == null) {
            PlatformActions.openUrl(update.pageUrl)
            return
        }
        if (_download.value is UpdateDownload.Downloading) return
        val file = UpdateFile(update)
        _status.value = null
        scope.launch {
            if (!downloader.isDownloaded(file) && !fetch(file)) return@launch
            _download.value = UpdateDownload.Idle
            when (PlatformActions.installApk(downloader.localPathFor(file))) {
                InstallResult.Started -> Unit
                InstallResult.NeedsPermission -> _status.value =
                    "Разрешите установку из LocalDoc AI в открывшихся настройках, вернитесь и нажмите «Скачать и установить» — файл уже загружен."
                InstallResult.Unsupported -> {
                    downloader.delete(file)
                    _status.value = "Не удалось открыть установщик — открыта страница релиза."
                    PlatformActions.openUrl(update.pageUrl)
                }
            }
        }
    }

    fun cancelDownload() {
        val current = _update.value ?: return
        if (current.downloadUrl != null && _download.value is UpdateDownload.Downloading) downloader.cancel(UpdateFile(current))
    }

    /** true — файл скачан целиком; иначе состояние [UpdateDownload.Failed] или [UpdateDownload.Idle] (отмена). */
    private suspend fun fetch(file: UpdateFile): Boolean {
        _download.value = UpdateDownload.Downloading(downloader.downloadedBytes(file), file.sizeBytes)
        var finished = false
        downloader.download(file).collect { state ->
            when (state) {
                is DownloadState.Progress -> _download.value = UpdateDownload.Downloading(state.downloadedBytes, state.totalBytes)
                is DownloadState.Done -> finished = true
                is DownloadState.Failed ->
                    _download.value = if (state.message == CANCELLED) UpdateDownload.Idle else UpdateDownload.Failed(state.message)
            }
        }
        return finished
    }

    private companion object {
        const val CANCELLED = "Загрузка отменена"
    }
}
