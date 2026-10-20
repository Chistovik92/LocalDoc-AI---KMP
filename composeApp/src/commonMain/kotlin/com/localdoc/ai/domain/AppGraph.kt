package com.localdoc.ai.domain

import com.localdoc.ai.book.BookImporter
import com.localdoc.ai.book.BookmarkRepository
import com.localdoc.ai.lang.PageTranslator
import com.localdoc.ai.book.LibraryRepository
import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.download.ModelDownloader
import com.localdoc.ai.engine.LlamaEngine
import com.localdoc.ai.model.ModelCatalog
import com.localdoc.ai.model.ModelSpec
import com.localdoc.ai.parser.ParsedDoc
import com.localdoc.ai.parser.PdfExtractor
import com.localdoc.ai.images.ImageLoader
import com.localdoc.ai.platform.FileSaver
import com.localdoc.ai.net.CookieJar
import com.localdoc.ai.net.SiteAuth
import com.localdoc.ai.net.SiteVault
import com.localdoc.ai.net.WebHttp
import com.localdoc.ai.net.asStorage
import com.localdoc.ai.net.asTransport
import com.localdoc.ai.parser.WebArticleParser
import com.localdoc.ai.platform.RawHttp
import com.localdoc.ai.platform.SecureStore
import com.localdoc.ai.platform.FileAccess
import com.localdoc.ai.platform.KeyValueStore
import com.localdoc.ai.rag.HashingEmbedder
import com.localdoc.ai.rag.RagRepository
import com.localdoc.ai.rag.VectorStore
import com.localdoc.ai.tts.PiperSpeechEngine
import com.localdoc.ai.tts.ReadAloudController
import com.localdoc.ai.tts.Speaker
import com.localdoc.ai.tts.SpeechRouter
import com.localdoc.ai.tts.VoicePackManager
import com.localdoc.ai.tts.VoicePackStorage
import com.localdoc.ai.tts.createPcmOutput
import com.localdoc.ai.tts.createPiperBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Сообщение чата. */
data class ChatMessage(
    val role: Role,
    val text: String
) {
    enum class Role { USER, ASSISTANT }
}

/** Документ, переданный извне (Share Intent / файловый менеджер). */
data class IncomingDoc(val type: IncomingType, val payload: String) {
    /** FILE — любой файл книги/документа (Uri или путь). */
    enum class IncomingType { URL, TEXT, FILE }
}

/** Быстрые действия с виджета рабочего стола и deep link (localdoc://…). */
enum class AppAction { CONTINUE_READING, OPEN_FILE, OPEN_URL, TOGGLE_PLAYBACK }

/**
 * Единая точка доступа к движку и состоянию приложения (DI-контейнер).
 */
object AppGraph {

    val kv: KeyValueStore = KeyValueStore()
    val engine: LlamaEngine = LlamaEngine()
    val downloader: ModelDownloader = ModelDownloader()
    /** Защищённое хранилище (Keystore / Keychain): пароли сайтов и cookie сессий. */
    val secure: SecureStore by lazy { SecureStore() }
    val cookieJar: CookieJar by lazy { CookieJar(secure.asStorage()) }
    val webHttp: WebHttp by lazy { WebHttp(RawHttp().asTransport(), cookieJar) }
    val siteVault: SiteVault by lazy { SiteVault(secure.asStorage(), cookieJar) }
    val siteAuth: SiteAuth by lazy { SiteAuth(webHttp, siteVault) }
    val images: ImageLoader by lazy { ImageLoader(files, kv, { webHttp }) }
    val webParser: WebArticleParser = WebArticleParser { webHttp }
    val pdfExtractor: PdfExtractor = PdfExtractor()
    val files: FileAccess = FileAccess()
    val importer: BookImporter by lazy { BookImporter(files, pdfExtractor) }
    val library: LibraryRepository by lazy { LibraryRepository(kv, files) }

    val readerSettings = PersistedSetting(kv, "reader_settings", ReaderSettings.serializer(), ReaderSettings())
    val voiceSettings = PersistedSetting(kv, "voice_settings", VoiceSettings.serializer(), VoiceSettings())
    val translationSettings = PersistedSetting(kv, "translation_settings", TranslationSettings.serializer(), TranslationSettings())

    /** Закладки в книгах и на веб-страницах. */
    val bookmarks: BookmarkRepository = BookmarkRepository({ kv.get(KEY_BOOKMARKS) }, { kv.put(KEY_BOOKMARKS, it) })

    /** Документы (ключи вида «book:id» / «web:id»), у которых сейчас включён перевод. */
    val translating = MutableStateFlow<Set<String>>(emptySet())

    private val ragRepository: RagRepository by lazy {
        RagRepository(VectorStore(kv), HashingEmbedder())
    }
    val pipeline: SmartPipeline by lazy {
        SmartPipeline(engine, ragRepository) { activeSpec?.template ?: com.localdoc.ai.model.ChatTemplate.CHATML }
    }

    /**
     * Скоуп уровня приложения: генерация не должна обрываться при смене вкладки
     * (rememberCoroutineScope экрана отменяется, когда экран уходит из композиции).
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Встроенные голоса Piper: скачивание, распаковка, удаление. */
    val voicePacks: VoicePackManager by lazy {
        VoicePackManager(VoicePackStorage(), ModelDownloader("voices", ".tar.bz2"), appScope, Dispatchers.Default)
    }

    /** Озвучка: системный синтезатор или встроенный Piper — по настройкам голоса. */
    val speaker: SpeechRouter by lazy {
        val builtin = PiperSpeechEngine(createPiperBackend(), createPcmOutput(), appScope, Dispatchers.Default) { id ->
            voicePacks.paths(id)
        }
        // Сохранённые настройки применяются сразу: шторка голосов может открыться раньше плеера
        SpeechRouter(Speaker(), builtin).apply {
            val v = voiceSettings.value.value
            v.engineId?.let(::setEngine)
            setRate(v.rate)
            setPitch(v.pitch)
            setVoice(v.voiceId)
        }
    }

    /** Постраничный перевод моделью; кэш и предзагрузка соседней страницы. */
    val translator: PageTranslator by lazy {
        PageTranslator({ text, target, source ->
            flow {
                check(engine.isLoaded()) { "Модель не загружена: выберите её в Настройки → Модели" }
                emitAll(pipeline.translate(text, target, source))
            }
        }, appScope)
    }

    /** Плеер чтения вслух — общий для книг и веб-страниц. */
    val readAloud: ReadAloudController by lazy { ReadAloudController(speaker, appScope) }

    /** Скачивание книг целиком и «Сохранить как…»: оглавление, главы и картинки собираются автоматически. */
    val fileSaver: FileSaver by lazy { FileSaver() }

    private val chapterSource = object : ChapterSource {
        override suspend fun load(url: String) = webParser.load(url)
        override suspend fun tableOfContents(tocUrl: String) = webParser.loadTableOfContents(tocUrl)
    }

    private val fileFetcher = FileFetcher { url ->
        val result = webHttp.get(url, maxBytes = WebHttp.MAX_DOWNLOAD_BYTES)
        // Страница входа или ошибка вместо файла — это не книга
        if (!result.isOk || result.contentType.startsWith("text/html")) null
        else DownloadedFile(fileNameOf(result.headers["content-disposition"], result.url), result.body)
    }

    val downloads: BookDownloadManager by lazy {
        BookDownloadManager(
            scope = appScope,
            library = library,
            downloader = NovelDownloader(chapterSource, fileFetcher, files.asNovelFiles()),
            parser = webParser,
            images = images,
            saver = fileSaver,
            resolveToc = { _, page -> webSession.resolveTableOfContents(WebDocument(page.book, "", page.links, 0)) }
        )
    }

    val updates: com.localdoc.ai.update.UpdateManager by lazy { com.localdoc.ai.update.UpdateManager(appScope) }

    val webSession: WebSession by lazy {
        WebSession(
            parser = webParser,
            library = library,
            pipeline = { pipeline },
            isModelLoaded = { loadedModelId.value != null },
            readAloud = { readAloud },
            scope = appScope,
            auth = { siteAuth },
            vault = { siteVault },
            autoNext = { readerSettings.value.value.autoNext }
        )
    }

    fun updateVoice(transform: (VoiceSettings) -> VoiceSettings) {
        val before = voiceSettings.value.value
        voiceSettings.update(transform)
        val after = voiceSettings.value.value
        if (after.engineId != before.engineId) {
            // Новый движок поднимается асинхронно: текущая реплика теряется — переозвучиваем её
            val wasPlaying = readAloud.state.value.playing
            if (wasPlaying) readAloud.pause()
            speaker.setEngine(after.engineId)
            speaker.setVoice(after.voiceId)
            if (wasPlaying) readAloud.play()
        }
        speaker.setRate(after.rate)
        speaker.setPitch(after.pitch)
        if (after.voiceId != before.voiceId || after.engineId != before.engineId) speaker.setVoice(after.voiceId)
    }

    /** Текущая генерация ответа (для кнопки «Стоп»). */
    var generationJob: Job? = null

    // --- Загрузки моделей (переживают смену вкладки) ---
    val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloadJobs = mutableMapOf<String, Job>()

    fun startDownload(spec: ModelSpec) {
        if (downloadJobs[spec.id]?.isActive == true) return
        setDownloadState(spec.id, DownloadState.Progress(downloader.downloadedBytes(spec), -1))
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            downloader.download(spec).collect { setDownloadState(spec.id, it) }
        }
        downloadJobs[spec.id] = job
        // Удаляем запись только своей задачи: после «Отменить» → «Скачать» там уже новая
        job.invokeOnCompletion { appScope.launch { if (downloadJobs[spec.id] === job) downloadJobs.remove(spec.id) } }
        job.start()
    }

    /** Отмена = отмена корутины: на iOS это единственный способ прервать NSURLSession. */
    fun cancelDownload(spec: ModelSpec) {
        downloader.cancel(spec)
        downloadJobs.remove(spec.id)?.cancel()
        clearDownloadState(spec.id)
    }

    fun clearDownloadState(specId: String) {
        downloadStates.value = downloadStates.value - specId
    }

    private fun setDownloadState(specId: String, state: DownloadState) {
        downloadStates.value = downloadStates.value + (specId to state)
    }

    // --- Состояние UI ---
    val currentDoc = MutableStateFlow<ParsedDoc?>(null)
    val incoming = MutableStateFlow<IncomingDoc?>(null)
    val pendingAction = MutableStateFlow<AppAction?>(null)
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    // Модель в памяти после перезапуска отсутствует — состояние восстанавливает restoreLastModel()
    val loadedModelId = MutableStateFlow<String?>(null)
    val isGenerating = MutableStateFlow(false)
    val statsTokensPerSec = MutableStateFlow(0f)

    var activeSpec: ModelSpec? = null
        private set

    fun requestAction(action: AppAction?) {
        pendingAction.value = action
    }

    /** Разбор deep link виджета iOS / Android: localdoc://continue, open-file, open-url, play. */
    fun handleDeepLink(url: String) {
        when (url.substringAfter("://").substringBefore('?').trimEnd('/')) {
            "continue" -> requestAction(AppAction.CONTINUE_READING)
            "open-file" -> requestAction(AppAction.OPEN_FILE)
            "open-url" -> requestAction(AppAction.OPEN_URL)
            "play" -> requestAction(AppAction.TOGGLE_PLAYBACK)
        }
    }

    fun acceptIncoming(item: IncomingDoc?) {
        incoming.value = item
    }

    fun setLoaded(spec: ModelSpec?) {
        activeSpec = spec
        loadedModelId.value = spec?.id
        if (spec != null) kv.put(KEY_LOADED_MODEL, spec.id) else kv.remove(KEY_LOADED_MODEL)
    }

    /** Повторно загружает модель, которая была активна до перезапуска приложения. */
    suspend fun restoreLastModel() {
        if (engine.isLoaded()) return
        val spec = ModelCatalog.models.firstOrNull { it.id == kv.get(KEY_LOADED_MODEL) } ?: return
        if (!downloader.isDownloaded(spec)) {
            setLoaded(null)
            return
        }
        val ok = engine.load(downloader.localPathFor(spec), spec.defaultContext, spec.defaultGpuLayers)
        setLoaded(if (ok) spec else null)
    }

    fun addMessage(message: ChatMessage) {
        messages.value = messages.value + message
    }

    fun updateLastAssistant(text: String) {
        val list = messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == ChatMessage.Role.ASSISTANT) {
            list[list.lastIndex] = list.last().copy(text = SmartPipeline.stripThinking(text))
            messages.value = list
        }
    }

    fun clearChat() {
        messages.value = emptyList()
    }

    /** Имя файла из `Content-Disposition` (filename*=UTF-8''… или filename="…"), иначе — последний сегмент адреса. */
    internal fun fileNameOf(disposition: String?, url: String): String {
        val star = disposition?.let { Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val plain = disposition?.let { Regex("filename\\s*=\\s*\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val raw = star ?: plain ?: url.substringBefore('?').substringAfterLast('/')
        return raw.trim().ifBlank { "book" }
    }

    private const val KEY_LOADED_MODEL = "loaded_model_id"
    private const val KEY_BOOKMARKS = "bookmarks_v1"
}
