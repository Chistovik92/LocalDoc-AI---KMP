package com.localdoc.ai.rag

import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.platform.SyncLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.TimeSource

/** Состояние семантической модели для экрана настроек. */
sealed interface SemanticModelState {
    data object NotInstalled : SemanticModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : SemanticModelState
    data object Verifying : SemanticModelState
    data object Loading : SemanticModelState
    data object Ready : SemanticModelState

    /** Модель скачана, но пользователь её отключил. */
    data object Disabled : SemanticModelState

    /** На платформе нет ONNX Runtime. */
    data class Unsupported(val reason: String) : SemanticModelState

    /** Устройство слабое: модель скачана, но не используется. */
    data class Rejected(val reason: String) : SemanticModelState
    data class Failed(val message: String) : SemanticModelState
}

/**
 * Скачивание, проверка, загрузка и удаление модели эмбеддингов. Пока модель не готова,
 * [embedder] отдаёт прежний [fallback] (хеширование признаков) — поиск работает всегда.
 */
class SemanticModelManager(
    private val files: SemanticFiles,
    private val storage: RagStorage,
    private val runtime: SemanticRuntime,
    private val fileSha256: (String) -> String?,
    private val totalMemoryMb: () -> Long,
    runtimeAvailable: Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val benchmark: (EmbeddingProvider) -> Long = ::measurePassageMillis,
    private val fallback: EmbeddingProvider = HashingEmbedder()
) {
    private val _state = MutableStateFlow<SemanticModelState>(SemanticModelState.NotInstalled)
    val state: StateFlow<SemanticModelState> = _state.asStateFlow()
    private val active = MutableStateFlow<EmbeddingProvider>(fallback)
    private var job: Job? = null
    private val lifecycleLock = SyncLock()
    private var activationGeneration = 0L

    init {
        if (!runtimeAvailable) {
            _state.value = SemanticModelState.Unsupported("ONNX Runtime пока недоступен на этой платформе")
        } else if (isInstalled()) {
            job = scope.launch { activate() }
        }
    }

    /** Текущий провайдер: e5, когда модель готова, иначе хеширование. */
    fun embedder(): EmbeddingProvider = active.value

    /** Пользователь включил модель (по умолчанию включена, как только скачана). */
    fun isEnabled(): Boolean = storage.get(ENABLED_KEY) != "0"

    fun isInstalled(): Boolean =
        storage.get(VERIFIED_KEY) == "1" && SemanticModelCatalog.all.all { files.isDownloaded(it) }

    fun download() {
        if (_state.value is SemanticModelState.Unsupported || job?.isActive == true) return
        if (isInstalled()) {
            // Ручной запуск — новый замер: старый отказ мог быть вызван троттлингом или старой policy.
            storage.remove(REJECTED_KEY)
            job = scope.launch { activate() }
            return
        }
        job = scope.launch {
            try {
                if (!fetchMissing()) return@launch
                if (!verify()) return@launch
                storage.put(VERIFIED_KEY, "1")
                storage.remove(REJECTED_KEY)
                storage.remove(ENABLED_KEY)
                activate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SemanticModelState.Failed(e.message ?: "ошибка загрузки модели")
            }
        }
    }

    fun cancel() {
        SemanticModelCatalog.all.forEach { files.cancel(it) }
        invalidateActivation()
        job?.cancel()
        job = null
        _state.value = if (isInstalled()) SemanticModelState.Ready else SemanticModelState.NotInstalled
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled()) return
        if (enabled) {
            storage.remove(ENABLED_KEY)
            storage.remove(REJECTED_KEY)
        } else {
            storage.put(ENABLED_KEY, "0")
        }
        if (!isInstalled()) return
        if (!enabled) invalidateActivation()
        job = scope.launch { if (enabled) activate() else deactivate(SemanticModelState.Disabled) }
    }

    suspend fun delete() {
        SemanticModelCatalog.all.forEach { files.cancel(it) }
        invalidateActivation()
        job?.cancel()
        job = null
        deactivate(SemanticModelState.NotInstalled)
        withContext(io) { SemanticModelCatalog.all.forEach { files.delete(it) } }
        listOf(VERIFIED_KEY, REJECTED_KEY, ENABLED_KEY).forEach(storage::remove)
    }

    /** Модель упала при работе: возвращаемся на хешированию, файлы остаются. */
    fun onEmbedderFailure(error: Throwable) {
        invalidateActivation()
        deactivate(SemanticModelState.Failed("модель дала сбой: ${error.message ?: "неизвестная ошибка"}"))
    }

    private fun invalidateActivation() = lifecycleLock.withLock { activationGeneration++ }

    private suspend fun fetchMissing(): Boolean {
        val total = SemanticModelCatalog.totalBytes
        var finished = 0L
        for (artifact in SemanticModelCatalog.all) {
            if (files.isDownloaded(artifact)) {
                finished += artifact.sizeBytes
                continue
            }
            var done = false
            files.download(artifact).collect { s ->
                when (s) {
                    is DownloadState.Progress ->
                        _state.value = SemanticModelState.Downloading(finished + s.downloadedBytes, total)
                    is DownloadState.Failed -> _state.value = SemanticModelState.Failed(s.message)
                    is DownloadState.Done -> done = true
                }
            }
            if (!done) return false
            finished += artifact.sizeBytes
        }
        return true
    }

    /** Сверяет SHA-256 каждого файла; повреждённый файл удаляется, чтобы докачка его не «чинила». */
    private suspend fun verify(): Boolean {
        _state.value = SemanticModelState.Verifying
        for (artifact in SemanticModelCatalog.all) {
            val actual = withContext(io) { fileSha256(files.pathFor(artifact)) }
            if (actual != artifact.sha256) {
                withContext(io) { files.delete(artifact) }
                _state.value = SemanticModelState.Failed(
                    "файл ${artifact.id} повреждён при скачивании (контрольная сумма не совпала), скачайте заново"
                )
                return false
            }
        }
        return true
    }

    private suspend fun activate() {
        if (!isEnabled()) return deactivate(SemanticModelState.Disabled)
        val generation = lifecycleLock.withLock { ++activationGeneration }
        val declined = storage.get(REJECTED_KEY) ?: SemanticModelPolicy.memoryVerdict(totalMemoryMb())
        if (declined != null) {
            storage.put(REJECTED_KEY, declined)
            return deactivate(SemanticModelState.Rejected(declined))
        }
        _state.value = SemanticModelState.Loading
        // Не используем withContext для синхронного load: отмена на выходе иначе теряет ссылку
        // на уже созданный native-провайдер. Ниже finally закрывает его до передачи в active.
        val provider = try {
            runtime.load(files.pathFor(SemanticModelCatalog.model), files.pathFor(SemanticModelCatalog.tokenizer))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return deactivate(SemanticModelState.Failed("не удалось запустить модель: ${e.message ?: "неизвестная ошибка"}"))
        }
        var handedOff = false
        try {
            val slow = SemanticModelPolicy.speedVerdict(benchmark(provider))
            currentCoroutineContext().ensureActive()
            val stale = lifecycleLock.withLock { generation != activationGeneration || !isEnabled() }
            if (stale || slow != null) {
                if (slow != null && !stale) storage.put(REJECTED_KEY, slow)
                return deactivate(if (stale && !isEnabled()) SemanticModelState.Disabled else SemanticModelState.Rejected(slow ?: "модель отключена"))
            }
            lifecycleLock.withLock {
                if (generation != activationGeneration || !isEnabled()) return@withLock false
                val previous = active.value
                active.value = provider
                handedOff = true
                _state.value = SemanticModelState.Ready
                previous
            }.let { previous -> if (handedOff && previous !== fallback) runtime.unload(previous as EmbeddingProvider) }
        } finally {
            if (!handedOff) runtime.unload(provider)
        }
    }

    private fun deactivate(next: SemanticModelState) {
        val previous = lifecycleLock.withLock {
            val old = active.value
            active.value = fallback
            _state.value = next
            old
        }
        if (previous !== fallback) runtime.unload(previous)
    }

    private companion object {
        const val VERIFIED_KEY = "semantic.verified"
        const val REJECTED_KEY = "semantic.rejected"
        const val ENABLED_KEY = "semantic.enabled"
    }
}

/** Медиана времени кодирования рабочего фрагмента, мс (после одного прогрева). */
fun measurePassageMillis(provider: EmbeddingProvider): Long {
    provider.encodePassage(SemanticModelPolicy.benchmarkPassage)
    val times = (1..SemanticModelPolicy.BENCHMARK_RUNS).map {
        val mark = TimeSource.Monotonic.markNow()
        provider.encodePassage(SemanticModelPolicy.benchmarkPassage)
        mark.elapsedNow().inWholeMilliseconds
    }
    return SemanticModelPolicy.median(times)
}
