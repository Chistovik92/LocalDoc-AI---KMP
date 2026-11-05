package com.localdoc.ai.tts

import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.download.ModelDownloader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Файловые операции с голосовыми пакетами (Android — files/voices, iOS — Documents/voices). */
expect class VoicePackStorage() {
    /** Каталог, куда распаковываются голоса. */
    fun rootDir(): String
    fun exists(path: String): Boolean
    fun deleteRecursively(path: String)
    fun rename(from: String, to: String): Boolean
    /**
     * Распаковывает tar.bz2 в [destDir] (создаётся), отбрасывая первый компонент пути.
     * Бросает исключение, если архив повреждён.
     */
    fun extractTarBz2(archivePath: String, destDir: String)
}

/** Состояние голосового пакета для экрана настроек. */
sealed interface VoicePackState {
    data object NotInstalled : VoicePackState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : VoicePackState
    data object Installing : VoicePackState
    data object Installed : VoicePackState
    data class Failed(val message: String) : VoicePackState
}

/**
 * Скачивание, распаковка и удаление голосов Piper. Загрузка докачивается после обрыва
 * (архив хранится рядом как .part), распаковка идёт во временный каталог и только
 * потом переименовывается — недораспакованный голос никогда не считается установленным.
 */
class VoicePackManager(
    private val storage: VoicePackStorage,
    private val downloader: ModelDownloader,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher
) {
    private val _states = MutableStateFlow<Map<String, VoicePackState>>(emptyMap())
    val states: StateFlow<Map<String, VoicePackState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    fun isInstalled(voice: PiperVoice): Boolean = paths(voice.id) != null

    /** Пути установленного голоса или null, если голоса нет (или он неполный). */
    fun paths(voiceId: String): PiperVoicePaths? {
        val voice = PiperVoiceCatalog.byId(voiceId) ?: return null
        val dir = dirOf(voice)
        val result = PiperVoicePaths(
            model = "$dir/${voice.modelFile}",
            tokens = "$dir/tokens.txt",
            dataDir = "$dir/espeak-ng-data"
        )
        return result.takeIf { storage.exists(it.model) && storage.exists(it.tokens) && storage.exists(it.dataDir) }
    }

    fun refresh() {
        _states.value = PiperVoiceCatalog.voices.associate { voice ->
            val active = _states.value[voice.id]
            voice.id to when {
                jobs[voice.id]?.isActive == true && active != null -> active
                isInstalled(voice) -> VoicePackState.Installed
                else -> VoicePackState.NotInstalled
            }
        }
    }

    fun download(voice: PiperVoice) {
        if (jobs[voice.id]?.isActive == true || isInstalled(voice)) return
        set(voice.id, VoicePackState.Downloading(downloader.downloadedBytes(voice), voice.sizeBytes))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Архив уже скачан, но распаковку прервали (приложение закрыли) — качать заново не нужно
            val archive = if (downloader.isDownloaded(voice)) downloader.localPathFor(voice) else fetch(voice)
            archive?.let { install(voice, it) }
        }
        jobs[voice.id] = job
        job.invokeOnCompletion { scope.launch { if (jobs[voice.id] === job) jobs.remove(voice.id) } }
        job.start()
    }

    /** Скачивает архив; путь к нему или null, если загрузка не удалась (состояние уже выставлено). */
    private suspend fun fetch(voice: PiperVoice): String? {
        var archive: String? = null
        downloader.download(voice).collect { state ->
            when (state) {
                is DownloadState.Progress -> set(voice.id, VoicePackState.Downloading(state.downloadedBytes, state.totalBytes))
                is DownloadState.Failed -> set(voice.id, VoicePackState.Failed(state.message))
                is DownloadState.Done -> archive = state.path
            }
        }
        return archive
    }

    private suspend fun install(voice: PiperVoice, archive: String) {
        set(voice.id, VoicePackState.Installing)
        val dir = dirOf(voice)
        val temp = "$dir.partial"
        val error = withContext(io) {
            runCatching {
                storage.deleteRecursively(temp)
                storage.extractTarBz2(archive, temp)
                storage.deleteRecursively(dir)
                check(storage.rename(temp, dir)) { "не удалось сохранить файлы голоса" }
            }.exceptionOrNull().also {
                // Архив больше не нужен; повреждённый тоже удаляем — иначе докачка его не исправит
                downloader.delete(voice)
                if (it != null) storage.deleteRecursively(temp)
            }
        }
        set(
            voice.id,
            when {
                error != null -> VoicePackState.Failed("Не удалось распаковать голос: ${error.message ?: "архив повреждён"}")
                isInstalled(voice) -> VoicePackState.Installed
                else -> VoicePackState.Failed("В архиве голоса нет нужных файлов")
            }
        )
    }

    fun cancel(voice: PiperVoice) {
        downloader.cancel(voice)
        jobs.remove(voice.id)?.cancel()
        set(voice.id, if (isInstalled(voice)) VoicePackState.Installed else VoicePackState.NotInstalled)
    }

    suspend fun delete(voice: PiperVoice) {
        cancel(voice)
        withContext(io) {
            storage.deleteRecursively(dirOf(voice))
            storage.deleteRecursively("${dirOf(voice)}.partial")
            downloader.delete(voice)
        }
        set(voice.id, VoicePackState.NotInstalled)
    }

    private fun dirOf(voice: PiperVoice) = "${storage.rootDir()}/${voice.id}"

    private fun set(id: String, state: VoicePackState) {
        _states.value = _states.value + (id to state)
    }
}
