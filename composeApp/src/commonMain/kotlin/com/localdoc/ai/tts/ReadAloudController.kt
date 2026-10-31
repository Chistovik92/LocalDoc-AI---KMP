package com.localdoc.ai.tts

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BookPager
import com.localdoc.ai.book.Chapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Почему озвучка остановилась сама. */
enum class ReadAloudStop { CHAPTER_END, BOOK_END }

data class ReadAloudState(
    /** Плеер открыт (идёт или стоит на паузе). */
    val active: Boolean = false,
    val playing: Boolean = false,
    /** Название книги/страницы — для уведомления, виджета и экрана блокировки. */
    val title: String = "",
    val segment: SpeechSegment? = null,
    /** Позиция фрагмента внутри текущей страницы и число фрагментов страницы. */
    val position: Int = 0,
    val total: Int = 0,
    /** Страница книги, которая сейчас звучит, и число страниц. */
    val page: Int = 0,
    val pageCount: Int = 0,
    val stoppedAt: ReadAloudStop? = null,
    /** Озвучку остановил таймер сна: позиция сохранена, фоновый сервис плеера больше не нужен. */
    val sleepStopped: Boolean = false,
    val error: String? = null
)

/**
 * Откуда плеер берёт текст страниц. Текст страницы подготавливается по требованию (например, переводится),
 * поэтому [blocks] — suspend: плеер дожидается страницы, не блокируя интерфейс.
 */
interface SpeechPageSource {
    val pager: BookPager

    /** Блоки страницы [page] в том виде, в каком они озвучиваются (оригинал или перевод). */
    suspend fun blocks(page: Int): List<Block>
}

/** Страницы готового списка блоков без обработки. */
class StaticPageSource(private val all: List<Block>, override val pager: BookPager) : SpeechPageSource {
    override suspend fun blocks(page: Int): List<Block> {
        val p = pager.page(page)
        return all.subList(p.startBlock.coerceAtMost(all.size), p.endBlock.coerceAtMost(all.size))
    }
}

/**
 * Плеер чтения вслух: последовательно отдаёт движку предложения, подсвечивает текущее,
 * поддерживает паузу, стоп и переходы по предложениям/абзацам.
 *
 * Постраничная подача: в работе только «окно» из двух страниц — текущей и следующей
 * (чтобы озвучка шла без пауз на стыке); при переходе на следующую страницу окно сдвигается, а
 * очередная страница подготавливается в фоне. Большая книга не сегментируется целиком.
 *
 * Устойчивость: фрагмент, который движок не смог озвучить (реклама, эмодзи, редкие символы),
 * пропускается — чтение продолжается. Остановка только после нескольких сбоев подряд.
 * В конце главы озвучка не переходит дальше сама, а предупреждает и ждёт пользователя.
 */
class ReadAloudController(
    private val engine: SpeechEngine,
    private val scope: CoroutineScope,
    /** События звука (звонок, чужие плееры, наушники); null — платформа их не отдаёт. */
    private val audioEvents: AudioInterruptions? = null
) {
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private var source: SpeechPageSource? = null
    private var chapterStarts: List<Int> = emptyList()

    /** Окно: фрагменты страницы [firstPage] и следующей непустой страницы [secondPage]. */
    private var segments: List<SpeechSegment> = emptyList()
    private var firstLen = 0
    private var firstPage = 0
    private var secondPage = -1
    private val pageCache = HashMap<Int, List<SpeechSegment>>()

    private var index = 0
    private var session = 0
    private var consecutiveFailures = 0
    private var loadJob: Job? = null
    /** Фоновая подготовка страницы после сдвига окна; пока она идёт, конец окна — не конец книги. */
    private var slideJob: Job? = null
    private var waitingForPage = false

    /** Пауза от прерывания звонком/чужим плеером — продолжится, когда прерывание закончится. */
    private var interruptedByCall = false

    /** Пауза от отключения наушников — продолжится при их подключении. */
    private var interruptedByUnplug = false

    /** Ключ документа, который сейчас озвучивается (id книги или URL страницы). */
    var documentKey: String? = null
        private set

    private var title = ""

    /** Таймер сна «до конца главы»: на границе главы голос не объявляет «Конец главы» и озвучка замолкает сама. */
    var quietChapterEnd = false

    private fun audioStart() = runCatching { audioEvents?.start() }.getOrDefault(Unit)
    private fun audioStop() = runCatching { audioEvents?.stop() }.getOrDefault(Unit)

    init {
        audioEvents?.setListener(
            onInterrupted = {
                if (_state.value.playing) {
                    interruptedByCall = true
                    pauseInternal()
                }
            },
            onRegained = {
                if (interruptedByCall) {
                    interruptedByCall = false
                    // Пауза могла быть снята пользователем во время звонка — не навязываем продолжение
                    if (!_state.value.playing && _state.value.active) play()
                }
            },
            onHeadphonesDisconnected = {
                if (_state.value.playing) {
                    interruptedByCall = false
                    interruptedByUnplug = true
                    pauseInternal()
                }
            },
            onHeadphonesConnected = {
                // Продолжаем, если пауза была от системы (звонок или наушники), а не от пользователя
                val resume = interruptedByUnplug || interruptedByCall
                interruptedByUnplug = false
                interruptedByCall = false
                if (resume && !_state.value.playing && _state.value.active) play()
            }
        )
    }

    fun start(key: String, blocks: List<Block>, chapters: List<Chapter>, fromBlock: Int, title: String = "", pager: BookPager? = null) =
        start(key, StaticPageSource(blocks, pager ?: BookPager.build(blocks, chapters)), chapters, fromBlock, title)

    fun start(key: String, source: SpeechPageSource, chapters: List<Chapter>, fromBlock: Int, title: String = "") {
        session++
        loadJob?.cancel()
        slideJob?.cancel()
        waitingForPage = false
        documentKey = key
        this.title = title
        this.source = source
        pageCache.clear()
        segments = emptyList()
        chapterStarts = chapters.map { it.startBlock }.filter { it > 0 }.sorted()
        val mySession = session
        loadJob = scope.launch {
            val opened = runCatchingPage { openWindow(source.pager.pageOfBlock(fromBlock), fromBlock) }
            if (mySession != session) return@launch
            if (!opened) {
                _state.value = ReadAloudState(error = "В тексте нет фрагментов для озвучки")
                return@launch
            }
            audioStart()
            play()
        }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        if (segments.isEmpty()) return
        if (index !in segments.indices) index = 0
        session++
        interruptedByCall = false
        // Прежняя реплика (другой документ, объявление «Конец главы») не должна звучать поверх
        engine.stop()
        consecutiveFailures = 0
        speakCurrent()
    }

    /** Громкость озвучки 0..1 (затухание таймера сна). */
    fun setVolume(volume: Float) = engine.setVolume(volume.coerceIn(0f, 1f))

    /** Сколько предложений осталось до конца текущей главы в уже подготовленном окне; null — граница не видна. */
    fun sentencesLeftInChapter(): Int? {
        if (segments.isEmpty() || index !in segments.indices) return null
        val current = segments[index]
        val boundary = chapterStarts.firstOrNull { it > current.block } ?: return null
        val next = segments.indexOfFirst { it.block >= boundary }
        return if (next >= 0) next - index else null
    }

    /** Остановка по таймеру сна: пауза на месте с пометкой для интерфейса и фонового сервиса. */
    fun sleepStop() {
        if (segments.isEmpty()) return
        session++
        engine.stop()
        engine.setVolume(1f)
        audioStop()
        interruptedByCall = false
        interruptedByUnplug = false
        publish(playing = false, sleepStopped = true)
    }

    /** Пауза, вызванная событием звука: не сбрасывает флаги прерываний. */
    private fun pauseInternal() {
        session++
        engine.stop()
        publish(playing = false)
    }

    fun pause() {
        session++
        engine.stop()
        // Пользователь нажал паузу сам: ни звонок, ни наушники не должны её снять
        interruptedByCall = false
        interruptedByUnplug = false
        publish(playing = false)
    }

    fun stop() {
        session++
        loadJob?.cancel()
        slideJob?.cancel()
        engine.stop()
        audioStop()
        interruptedByCall = false
        interruptedByUnplug = false
        segments = emptyList()
        source = null
        pageCache.clear()
        documentKey = null
        _state.value = ReadAloudState()
    }

    fun nextSentence() = seek(index + 1)
    fun previousSentence() = seek(index - 1)

    fun nextParagraph() {
        val current = segments.getOrNull(index)?.block ?: return
        seek(segments.indexOfFirst { it.block > current }.takeIf { it >= 0 } ?: segments.lastIndex)
    }

    fun previousParagraph() {
        val current = segments.getOrNull(index) ?: return
        // В середине абзаца — в его начало, иначе — в начало предыдущего
        val paragraphStart = segments.indexOfFirst { it.block == current.block }
        val target = if (index > paragraphStart) {
            paragraphStart
        } else {
            val prevBlock = segments.getOrNull(paragraphStart - 1)?.block
            if (prevBlock == null) paragraphStart - 1 else segments.indexOfFirst { it.block == prevBlock }
        }
        seek(target)
    }

    /** Начать чтение с указанного абзаца (тап по тексту при активном плеере, переход по оглавлению). */
    fun jumpToBlock(block: Int) {
        val src = source ?: return
        val inWindow = segments.indexOfFirst { it.block >= block }
        if (inWindow >= 0 && src.pager.pageOfBlock(block) in listOf(firstPage, secondPage)) {
            seek(inWindow)
            return
        }
        // Страница вне окна — открываем окно вокруг неё
        reopen(block, _state.value.playing)
    }

    private fun reopen(block: Int, resume: Boolean) {
        val src = source ?: return
        session++
        engine.stop()
        loadJob?.cancel()
        slideJob?.cancel()
        val mySession = session
        loadJob = scope.launch {
            val opened = runCatchingPage { openWindow(src.pager.pageOfBlock(block), block) }
            if (mySession != session || !opened) return@launch
            if (resume) play() else publish(playing = false)
        }
    }

    private fun seek(target: Int) {
        if (segments.isEmpty()) return
        if (target < 0) {
            seekPreviousPage()
            return
        }
        index = target.coerceIn(0, segments.lastIndex)
        if (_state.value.playing) {
            session++
            engine.stop()
            speakCurrent()
        } else {
            publish(playing = false)
        }
    }

    /** Шаг назад с первого фрагмента страницы: открываем окно с предыдущей страницей. */
    private fun seekPreviousPage() {
        val first = segments.firstOrNull() ?: return
        val src = source ?: return
        val previous = (src.pager.pageOfBlock(first.block) - 1 downTo 0)
        val wasPlaying = _state.value.playing
        session++
        engine.stop()
        loadJob?.cancel()
        slideJob?.cancel()
        val mySession = session
        loadJob = scope.launch {
            for (page in previous) {
                val segs = pageSegments(page)
                if (segs.isEmpty()) continue
                if (mySession != session) return@launch
                openWindow(page, segs.last().block, startAtLast = true)
                if (mySession != session) return@launch
                if (wasPlaying) play() else publish(playing = false)
                return@launch
            }
            if (mySession == session) {
                index = 0
                if (wasPlaying) play() else publish(playing = false)
            }
        }
    }

    private fun speakCurrent() {
        val segment = segments.getOrNull(index) ?: return
        val mySession = session
        publish(playing = true)
        // Следующее предложение той же главы движок может синтезировать, пока звучит текущее
        val upcoming = segments.getOrNull(index + 1)?.takeIf { !crossesChapter(segment, it) }
        engine.speak(segment.text) { success ->
            scope.launch { if (mySession == session) onSegmentFinished(success) }
        }
        if (upcoming != null && mySession == session) engine.prefetch(upcoming.text)
    }

    private fun crossesChapter(from: SpeechSegment, to: SpeechSegment): Boolean =
        chapterStarts.any { it in (from.block + 1)..to.block }

    private fun onSegmentFinished(success: Boolean) {
        if (success) consecutiveFailures = 0 else consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            session++
            publish(playing = false, error = "Голосовой движок не отвечает. Выберите другой голос в настройках озвучки.")
            return
        }
        advance()
    }

    private fun advance() {
        val finished = segments[index]
        val nextIndex = index + 1
        if (nextIndex > segments.lastIndex) {
            if (slideJob?.isActive == true) {
                // Следующая страница ещё готовится (например, переводится) — ждём её
                waitingForPage = true
                return
            }
            finishWith(ReadAloudStop.BOOK_END, "Текст закончился.")
            return
        }
        val next = segments[nextIndex]
        index = nextIndex
        if (crossesChapter(finished, next)) {
            finishWith(ReadAloudStop.CHAPTER_END, "Конец главы.")
            return
        }
        if (secondPage >= 0 && index >= firstLen) slideWindow()
        speakCurrent()
    }

    /** Текущая страница дочитана: вторая страница окна становится первой, следующая готовится в фоне. */
    private fun slideWindow() {
        segments = segments.subList(firstLen, segments.size).toList()
        index -= firstLen
        firstLen = segments.size
        firstPage = secondPage
        secondPage = -1
        pageCache.keys.removeAll { it < firstPage - 1 }
        val mySession = session
        slideJob?.cancel()
        slideJob = scope.launch {
            val next = nextNonEmptyPage(firstPage)
            if (mySession != session) return@launch
            if (next != null) {
                segments = segments + next.second
                secondPage = next.first
            }
            if (waitingForPage) {
                waitingForPage = false
                if (index + 1 <= segments.lastIndex) advance() else finishWith(ReadAloudStop.BOOK_END, "Текст закончился.")
            }
        }
    }

    /** Предупреждение голосом и остановка: дальше пользователь решает сам. */
    private fun finishWith(reason: ReadAloudStop, announcement: String) {
        session++
        if (quietChapterEnd) {
            // Таймер сна: тихая остановка, без голоса и без автоперехода к следующей главе
            engine.setVolume(1f)
            publish(playing = false, sleepStopped = true)
            return
        }
        publish(playing = false, stoppedAt = reason)
        engine.speak(announcement) { }
    }

    // --- Окно страниц ---

    private suspend fun pageSegments(page: Int): List<SpeechSegment> {
        pageCache[page]?.let { return it }
        val src = source ?: return emptyList()
        val firstBlock = src.pager.page(page).startBlock
        val segs = SpeechSegmenter.segments(src.blocks(page), firstBlock)
        pageCache[page] = segs
        return segs
    }

    private suspend fun nextNonEmptyPage(after: Int): Pair<Int, List<SpeechSegment>>? {
        val last = (source?.pager?.size ?: 0) - 1
        var page = after + 1
        while (page <= last) {
            val segs = pageSegments(page)
            if (segs.isNotEmpty()) return page to segs
            page++
        }
        return null
    }

    /**
     * Открывает окно, начиная со страницы, где есть фрагменты от блока [fromBlock]; если таких нет —
     * с начала книги. false — в книге нечего озвучивать.
     */
    private suspend fun openWindow(page: Int, fromBlock: Int, startAtLast: Boolean = false): Boolean {
        val src = source ?: return false
        val last = src.pager.size - 1
        var found: Pair<Int, List<SpeechSegment>>? = null
        for (p in page..last) {
            val segs = pageSegments(p)
            if (segs.any { it.block >= fromBlock }) { found = p to segs; break }
        }
        if (found == null) {
            for (p in 0..last) {
                val segs = pageSegments(p)
                if (segs.isNotEmpty()) { found = p to segs; break }
            }
        }
        val (firstIndex, first) = found ?: return false
        val next = nextNonEmptyPage(firstIndex)
        segments = first + (next?.second ?: emptyList())
        firstLen = first.size
        firstPage = firstIndex
        secondPage = next?.first ?: -1
        index = if (startAtLast) first.lastIndex else first.indexOfFirst { it.block >= fromBlock }.takeIf { it >= 0 } ?: 0
        return true
    }

    private suspend fun runCatchingPage(block: suspend () -> Boolean): Boolean =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            false
        }

    private fun publish(playing: Boolean, stoppedAt: ReadAloudStop? = null, error: String? = null, sleepStopped: Boolean = false) {
        val onSecond = secondPage >= 0 && index >= firstLen
        _state.value = ReadAloudState(
            active = segments.isNotEmpty(),
            playing = playing,
            title = title,
            segment = segments.getOrNull(index),
            position = if (onSecond) index - firstLen else index,
            total = if (onSecond) segments.size - firstLen else firstLen,
            page = if (onSecond) secondPage else firstPage,
            pageCount = source?.pager?.size ?: 0,
            stoppedAt = stoppedAt,
            sleepStopped = sleepStopped,
            error = error
        )
    }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 4
    }
}
