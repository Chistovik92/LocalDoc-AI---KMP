package com.localdoc.ai.domain

import com.localdoc.ai.backup.BackupService
import com.localdoc.ai.backup.ScheduledBackup
import com.localdoc.ai.storage.KvAccess
import com.localdoc.ai.storage.StorageMigrations
import com.localdoc.ai.sync.BookLinkedCollection
import com.localdoc.ai.sync.ProgressCollection
import com.localdoc.ai.sync.ShelfCollection
import com.localdoc.ai.sync.SyncEngine
import com.localdoc.ai.backup.BackupFormat
import com.localdoc.ai.backup.BackupStorage
import com.localdoc.ai.backup.RestoreResult
import com.localdoc.ai.book.BookImporter
import com.localdoc.ai.book.BookmarkRepository
import com.localdoc.ai.lang.PageTranslator
import com.localdoc.ai.book.LibraryRepository
import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.download.ModelDownloader
import com.localdoc.ai.engine.LlamaEngine
import com.localdoc.ai.model.ModelCatalog
import com.localdoc.ai.model.ModelSpec
import com.localdoc.ai.notes.AnnotationRepository
import com.localdoc.ai.notes.DictionaryRepository
import com.localdoc.ai.search.FullTextIndex
import com.localdoc.ai.search.LibrarySearch
import com.localdoc.ai.parser.ParsedDoc
import com.localdoc.ai.parser.PdfExtractor
import com.localdoc.ai.catalog.CatalogImportResult
import com.localdoc.ai.catalog.CatalogItem
import com.localdoc.ai.catalog.CatalogRepository
import com.localdoc.ai.catalog.ContentReader
import com.localdoc.ai.catalog.ScanSource
import com.localdoc.ai.images.ImageLoader
import com.localdoc.ai.platform.DeviceScanner
import com.localdoc.ai.platform.ScannedFile
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
import com.localdoc.ai.platform.PlatformActions
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
import kotlinx.coroutines.withContext

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

    /** Доступ к KV для миграций, копий и синхронизации. */
    private val kvAccess = object : KvAccess {
        override fun getValue(key: String) = kv.get(key)
        override fun putValue(key: String, value: String) = kv.put(key, value)
        override fun removeValue(key: String) = kv.remove(key)
    }

    // Миграции хранилища идут раньше любого чтения данных репозиториями ниже
    init {
        StorageMigrations(kvAccess).run()
    }

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
    val library: LibraryRepository by lazy { LibraryRepository(kv, files) { id -> appScope.launch(indexDispatcher) { search.invalidate(id) } } }

    val readerSettings = PersistedSetting(kv, "reader_settings", ReaderSettings.serializer(), ReaderSettings())
    val voiceSettings = PersistedSetting(kv, "voice_settings", VoiceSettings.serializer(), VoiceSettings())
    val translationSettings = PersistedSetting(kv, "translation_settings", TranslationSettings.serializer(), TranslationSettings())

    /** Закладки в книгах и на веб-страницах. */
    val bookmarks: BookmarkRepository = BookmarkRepository({ kv.get(KEY_BOOKMARKS) }, { kv.put(KEY_BOOKMARKS, it) })

    /** Цитаты и заметки в книгах. */
    val annotations: AnnotationRepository = AnnotationRepository({ kv.get(KEY_ANNOTATIONS) }, { kv.put(KEY_ANNOTATIONS, it) })

    /** Личный словарь: слова, выделенные в тексте, с переводом или определением. */
    val dictionary: DictionaryRepository = DictionaryRepository({ kv.get(KEY_DICTIONARY) }, { kv.put(KEY_DICTIONARY, it) })

    private val searchIndex: FullTextIndex by lazy {
        FullTextIndex(files.asNovelFiles(), { kv.get(KEY_SEARCH_INDEX) }, { kv.put(KEY_SEARCH_INDEX, it) })
    }

    /** Все операции с индексом идут по одной на этом диспетчере: индекс не рассчитан на параллельную запись. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val indexDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Поиск по тексту книг библиотеки и по каталогу устройства; индекс лежит на диске и обновляется по изменениям. */
    private val search: LibrarySearch by lazy {
        LibrarySearch(searchIndex, { library.entries.value }, { id -> library.load(id)?.blocks?.map { it.text } }, { catalog.items.value })
    }

    suspend fun searchContent(query: String) = withContext(indexDispatcher) { search.search(query) }

    /** Ход обновления поискового индекса: null — не идёт, иначе доля готового (0..1). */
    val searchIndexProgress = MutableStateFlow<Float?>(null)
    private var searchSync: Job? = null
    private var searchSyncAgain = false

    /** Приводит индекс в соответствие с библиотекой и каталогом; вызов во время работы запускает ещё один проход после неё. */
    fun syncSearchIndex() {
        if (searchSync?.isActive == true) {
            searchSyncAgain = true
            return
        }
        searchSync = appScope.launch {
            try {
                do {
                    searchSyncAgain = false
                    searchIndexProgress.value = 0f
                    withContext(indexDispatcher) {
                        search.sync { done, total -> searchIndexProgress.value = done.toFloat() / total.coerceAtLeast(1) }
                    }
                } while (searchSyncAgain)
            } finally {
                searchIndexProgress.value = null
            }
        }
    }

    private val backupStorage = object : BackupStorage {
        override fun getValue(key: String) = kv.get(key)
        override fun putValue(key: String, value: String) = kv.put(key, value)
        override fun removeValue(key: String) = kv.remove(key)
        override fun readFile(name: String) = files.readAppFile(name)
        override fun writeFile(name: String, bytes: ByteArray) = files.writeAppFile(name, bytes)
        override fun listFiles(prefix: String) = files.listAppFiles(prefix)
        override fun deleteFiles(prefix: String) = files.deleteAppFiles(prefix)
    }

    /** Резервная копия библиотеки и настроек одним файлом (без паролей сайтов). */
    val backup: BackupService by lazy { BackupService(backupStorage, PlatformActions.appVersion) }

    /** Копия по расписанию: проверяется при запуске приложения. */
    val scheduledBackup: ScheduledBackup by lazy { ScheduledBackup(kvAccess, backupStorage, backup) }

    /** Синхронизация прогресса, закладок, цитат и подборок (зашифрованный файл в облаке; пароли сайтов не входят). */
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            collections = listOf(
                ProgressCollection(backupStorage),
                BookLinkedCollection.bookmarks(backupStorage),
                BookLinkedCollection.annotations(backupStorage),
                ShelfCollection({ catalog.shelves.value }, catalog::replaceShelves, { catalog.items.value }, { library.entries.value })
            ),
            loadState = { kv.get(KEY_SYNC_STATE) },
            saveState = { kv.put(KEY_SYNC_STATE, it) },
            // Перед первой записью синхронизации в данные устройства — копия на случай, если что-то пойдёт не так
            safetyCopy = { files.writeAppFile("presync-backup.${BackupFormat.EXTENSION}", backup.create()) },
            onApplied = { kinds ->
                if ("progress" in kinds) library.reload()
                if ("bookmark" in kinds) bookmarks.reload()
                if ("annotation" in kinds) annotations.reload()
            }
        )
    }

    /** Восстанавливает данные из копии и обновляет всё, что держит их в памяти. */
    fun restoreBackup(bytes: ByteArray): RestoreResult {
        val result = backup.restore(bytes)
        if (result is RestoreResult.Restored) {
            library.reload()
            bookmarks.reload()
            annotations.reload()
            dictionary.reload()
            readerSettings.reload()
            voiceSettings.reload()
            appScope.launch { applyVoiceSettings() }
            translationSettings.reload()
            appScope.launch(indexDispatcher) { searchIndex.clear() }
            syncSearchIndex()
        }
        return result
    }

    /** Документы (ключи вида «book:id» / «web:id»), у которых сейчас включён перевод. */
    val translating = MutableStateFlow<Set<String>>(emptySet())

    private val ragRepository: RagRepository by lazy {
        RagRepository(VectorStore(kv), HashingEmbedder())
    }
    val pipeline: SmartPipeline by lazy {
        SmartPipeline(engine, ragRepository) { activeSpec?.template ?: com.localdoc.ai.model.ChatTemplate.CHATML }
    }

    /** Загруженная модель в роли помощника, разбивающего книгу на главы. */
    val chapterModel = com.localdoc.ai.book.ChapterModel { system, user, maxTokens ->
        check(engine.isLoaded()) { "Модель не загружена: выберите её в Настройки → Модели" }
        pipeline.complete(system, user, maxTokens)
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

    /** Применяет сохранённые настройки голоса к уже созданному синтезатору (после восстановления из копии). */
    private fun applyVoiceSettings() {
        val v = voiceSettings.value.value
        speaker.setEngine(v.engineId)
        speaker.setRate(v.rate)
        speaker.setPitch(v.pitch)
        speaker.setVoice(v.voiceId)
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

    /** Каталог книг на устройстве: сканирование, полки, подборки. */
    val deviceScanner: DeviceScanner by lazy { DeviceScanner() }

    val catalog: CatalogRepository by lazy {
        CatalogRepository(
            source = object : ScanSource {
                override suspend fun scan(rootId: String, extensions: Set<String>, onFound: (ScannedFile) -> Unit) =
                    deviceScanner.scan(rootId, extensions, onFound)
            },
            reader = ContentReader { uri, maxBytes -> runCatching { files.readBytes(uri) }.getOrNull()?.takeIf { it.size <= maxBytes } },
            store = files.asNovelFiles(),
            scope = appScope
        )
    }

    /** Добавляет найденные файлы в библиотеку; ошибки по отдельным файлам не прерывают остальные. */
    suspend fun addFromCatalog(items: List<CatalogItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): CatalogImportResult {
        var added = 0
        val failed = ArrayList<String>()
        items.forEachIndexed { index, item ->
            try {
                val entry = library.add(importer.import(item.uri))
                catalog.markAdded(item.uri, entry.id)
                added++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.add("${item.name}: ${e.message ?: "не удалось открыть"}")
            }
            onProgress(index + 1, items.size)
        }
        return CatalogImportResult(added, failed)
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
    private const val KEY_ANNOTATIONS = "annotations_v1"
    private const val KEY_DICTIONARY = "dictionary_v1"
    private const val KEY_SEARCH_INDEX = "search_index_v1"
    private const val KEY_SYNC_STATE = "sync_state_v1"
}
