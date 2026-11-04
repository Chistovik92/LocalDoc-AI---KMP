package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.NormalizeQuotes
import com.localdoc.ai.domain.SmartPipeline
import com.localdoc.ai.platform.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * Что именно переведено: документ, страница, язык перевода и (если задан) язык оригинала.
 * [layout] — размер страницы в символах: при другой разбивке тот же номер страницы — другие абзацы.
 */
@Serializable
data class PageKey(val docKey: String, val page: Int, val target: String, val source: String?, val layout: Int = 0)

/**
 * Перевод книги «на лету» постранично: каждая страница переводится блок за блоком локальной моделью,
 * результат кэшируется (несколько последних страниц), соседняя страница готовится заранее.
 * Движок генерирует по одному запросу, поэтому переводы идут по очереди; ИИ-суфлёр может
 * притормозить очередь через [hold]/[release] и дождаться окончания текущего блока [awaitIdle].
 */
class PageTranslator(
    private val translateText: (text: String, target: Language, source: Language?) -> Flow<String>,
    private val scope: CoroutineScope,
    private val maxCached: Int = 6,
    /** Долговременный стор перевода; null — перевод живёт только в памяти (тесты). */
    private val store: TranslationStore? = null,
    /**
     * Блоки переводятся параллельно (несколькими запросами сразу): так работает внешний
     * переводчик страниц (Google Translate) — его эндпоинт спокойно держит пачку, и перевод
     * страницы не растягивается на минуты. Локальная модель генерирует по одному запросу —
     * для неё параллелизм отключён (по умолчанию).
     */
    private val parallelBlocks: Int = 1
) {
    // Сбой перевода одной страницы не должен ронять родительский скоуп (экран, приложение)
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    /** Мьютекс генерации одного блока: движок отвечает на один запрос за раз. На всю страницу не берём —
     *  иначе предзагрузка соседней страницы встаёт в очередь поперёк той, что читает пользователь. */
    private val gate = Mutex()
    private val cache = LinkedHashMap<PageKey, List<Block>>()
    private val jobs = HashMap<PageKey, Deferred<List<Block>>>()
    /** Частично переведённые страницы: отменённая страница не теряет готовые блоки и потом
     *  доканчивается с того же места, модель не переводит то же самое заново. */
    private val partials = LinkedHashMap<PageKey, List<Block?>>()

    private val _progress = MutableStateFlow<Map<PageKey, List<Block?>>>(emptyMap())
    /** Переведённые на данный момент блоки страниц в работе (null — ещё не готов). */
    val progress: StateFlow<Map<PageKey, List<Block?>>> = _progress.asStateFlow()

    private val _generating = MutableStateFlow(false)
    /** Идёт генерация перевода одного блока (движок занят). */
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var holdCount = 0
    private var released = CompletableDeferred(Unit)

    fun cached(key: PageKey): List<Block>? = cache[key]

    /** Удаляет сохранённые на диске переводы документа (книга удалена) и отменяет идущий перевод. */
    fun clearDoc(docKey: String) {
        cancelDoc(docKey)
        store?.clearDoc(docKey)
    }

    fun clearError() {
        _error.value = null
    }

    /** Запускает (или возвращает уже идущий) перевод страницы; готовые страницы отдаются из кэша (или с диска). */
    fun request(key: PageKey, blocks: List<Block>, target: Language, source: Language?): Deferred<List<Block>> {
        DebugLog.d("translation request key=$key blocks=${blocks.size} target=${target.code}")
        cache[key]?.let { return CompletableDeferred(it) }
        // Перевод уже сохранён с прошлой сессии — отдаём его и не тратим модель
        store?.load(key)?.let { cached ->
            cache[key] = cached
            while (cache.size > maxCached) cache.remove(cache.keys.first())
            return CompletableDeferred(cached)
        }
        jobs[key]?.let { return it }
        val resume = partials.remove(key)
        val job = work.async {
            try {
                DebugLog.d("translation start key=$key")
                translatePage(key, blocks, target, source, resume)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                DebugLog.e("translation failed key=$key", t)
                _error.value = "Не удалось перевести: ${t.message ?: t::class.simpleName}"
                throw t
            } finally {
                _progress.value = _progress.value - key
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { if (jobs[key] === job) jobs.remove(key) }
        return job
    }

    suspend fun translate(key: PageKey, blocks: List<Block>, target: Language, source: Language?): List<Block> =
        request(key, blocks, target, source).await()

    /** Отменяет переводы страниц, которые больше не нужны: страницы дальше [radius] от текущей и с другой
     *  настройкой (язык, разбивка). Страницу с уже переведёнными блоками не трогаем — локальная модель
     *  переводит страницу долго, и отменять её на каждом листании нельзя: ни одна не доходит до конца. */
    fun cancelFar(keep: PageKey, radius: Int = 1) {
        DebugLog.d("translation cancelFar keep=$keep radius=$radius jobs=${jobs.size}")
        jobs.keys.filter { key ->
            key.docKey == keep.docKey &&
                (key.target != keep.target || key.layout != keep.layout || kotlin.math.abs(key.page - keep.page) > radius)
        }.forEach { key ->
            jobs.remove(key)?.also { job ->
                // Занятая страница — та, где уже есть хоть один переведённый блок: пусть дотранслируется.
                // Стартовый нулевой прогресс здесь не считается, иначе не отменялась бы ни одна.
                val busy = _progress.value[key]?.any { it != null } == true
                if (!busy) job.cancel()
            }
            // Смена настройки делает частичный перевод бесполезным: вокруг страницы его не удерживаем
            if (partials[key].orEmpty().all { it == null }) partials.remove(key)
        }
    }

    /** Отменяет все переводы документа: экран закрыт или документ перешёл к другому переводчику. */
    fun cancelDoc(docKey: String) {
        jobs.keys.filter { it.docKey == docKey }.forEach { key ->
            jobs.remove(key)?.cancel()
            partials.remove(key)
        }
    }

    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cache.clear()
        partials.clear()
        _progress.value = emptyMap()
        _error.value = null
    }

    fun hold() {
        if (holdCount++ == 0) released = CompletableDeferred()
    }

    fun release() {
        if (holdCount > 0 && --holdCount == 0) released.complete(Unit)
    }

    /** Ждёт, пока движок освободится от перевода текущего блока. */
    suspend fun awaitIdle() {
        _generating.first { !it }
    }

    private suspend fun translatePage(key: PageKey, blocks: List<Block>, target: Language, source: Language?, resume: List<Block?>? = null): List<Block> {
        val pageLanguage = LanguageDetector.detect(blocks.joinToString("\n") { it.text })
        val alreadyInTarget = Languages.sameLanguage(pageLanguage, target.code)
        val result = arrayOfNulls<Block>(blocks.size)
        // Отданные уже переведённые блоки: страница продолжается с того места, на котором оборвалась
        resume?.forEachIndexed { i, block -> if (block != null && i < result.size) result[i] = block }
        val toTranslate = blocks.withIndex().filter { !alreadyInTarget && needsTranslation(it.value) && result[it.index] == null }
        var failures = 0
        publishProgress(key, result)
        if (parallelBlocks <= 1) {
            // Локальная модель генерирует по одному запросу. Мьютекс держим только на один блок:
            // соседние страницы двигаются между блоками, а отмена не выкидывает уже переведённое.
            var succeeded = 0
            for ((i, block) in blocks.withIndex()) {
                if (result[i] != null || alreadyInTarget || !needsTranslation(block)) {
                    if (result[i] == null) result[i] = block
                } else {
                    try {
                        result[i] = gate.withLock { translateBlock(block, target, source) }
                        succeeded++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        if (succeeded == 0) throw t
                        // Один блок не перевёлся: он останется в оригинале, дальнейшие переводим дальше
                        println("PageTranslator: блок $i не переведён — ${t.message ?: t::class.simpleName}")
                        failures++
                    }
                }
                publishProgress(key, result)
            }
        } else {
            // Внешний переводчик страниц: абзацы идут пачкой (эндпоинт держит параллельные запросы),
            // готовые появляются на экране по мере ответа — порядок в странице сохраняется.
            // Пачка держится небольшой: открытый эндпоинт Google отвечает 429 на слишком частые запросы.
            toTranslate.forEach { (i, _) -> result[i] = null }
            val semaphore = Semaphore(parallelBlocks)
            coroutineScope {
                toTranslate.forEach { (i, block) ->
                    launch {
                        semaphore.withPermit {
                            try {
                                result[i] = translateBlock(block, target, source)
                                publishProgress(key, result)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                // Блок не перевёлся: останется в оригинале, страница при этом не рушится
                                println("PageTranslator: блок $i не переведён — ${t.message ?: t::class.simpleName}")
                                failures++
                            }
                        }
                    }
                }
            }
        }
        val done = result.mapIndexed { i, b -> b ?: blocks[i] }
        if (failures > 0) {
            // Страница переведена не до конца: в долговременный стор не пишем, чтобы модель позже
            // докончила её с того же места, а до тех пор показывали оригинал неудавшихся абзацев
            return done
        }
        cache[key] = done
        while (cache.size > maxCached) cache.remove(cache.keys.first())
        partials.remove(key)
        store?.save(key, done)
        return done
    }

    /** Прогресс страницы по готовым блокам; частичный снимок остаётся отдельно, чтобы пережить отмену. */
    private fun publishProgress(key: PageKey, result: Array<Block?>) {
        val snapshot = result.toList()
        _progress.value = _progress.value + (key to snapshot)
        partials.remove(key)
        partials[key] = snapshot
        while (partials.size > MAX_PARTIALS) {
            val victim = partials.keys.firstOrNull { !jobs.containsKey(it) } ?: break
            partials.remove(victim)
        }
    }

    private fun needsTranslation(block: Block) = block.type != BlockType.CODE && block.type != BlockType.IMAGE && block.text.any { it.isLetter() }

    private suspend fun translateBlock(block: Block, target: Language, source: Language?): Block {
        val parts = splitForTranslation(block.text, MAX_CHUNK_CHARS).map { chunk ->
            while (holdCount > 0) released.await()
            _generating.value = true
            try {
                val raw = translateText(chunk, target, source).toList().joinToString("")
                NormalizeQuotes.map(SmartPipeline.stripThinking(raw).trim().ifBlank { chunk })
            } finally {
                _generating.value = false
            }
        }
        // Выделения привязаны к символам оригинала — в переводе они бессмысленны
        return Block(block.type, parts.joinToString(" "), block.level)
    }

    companion object {
        private const val MAX_CHUNK_CHARS = 1_200
        /** Сколько частично переведённых страниц держим в памяти, не считая идущих сейчас. */
        private const val MAX_PARTIALS = 12
        private val SENTENCE_BREAK = Regex("(?<=[.!?…。！？])\\s+")

        /** Делит длинный абзац на части по предложениям, чтобы запрос влез в контекст модели. */
        fun splitForTranslation(text: String, maxChars: Int): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val result = ArrayList<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isNotBlank()) result.add(current.toString().trim())
                current.clear()
            }
            for (sentence in text.split(SENTENCE_BREAK)) {
                if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) flush()
                if (sentence.length > maxChars) {
                    sentence.chunked(maxChars).forEach { piece -> flush(); current.append(piece); flush() }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                }
            }
            flush()
            return result
        }
    }
}
