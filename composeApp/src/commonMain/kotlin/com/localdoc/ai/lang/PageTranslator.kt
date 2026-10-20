package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.domain.SmartPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Что именно переведено: документ, страница, язык перевода и (если задан) язык оригинала. */
data class PageKey(val docKey: String, val page: Int, val target: String, val source: String?)

/**
 * Перевод книги «на лету» постранично: каждая страница переводится блок за блоком локальной моделью,
 * результат кэшируется (несколько последних страниц), соседняя страница готовится заранее.
 * Движок генерирует по одному запросу, поэтому переводы идут по очереди; ИИ-суфлёр может
 * притормозить очередь через [hold]/[release] и дождаться окончания текущего блока [awaitIdle].
 */
class PageTranslator(
    private val translateText: (text: String, target: Language, source: Language?) -> Flow<String>,
    private val scope: CoroutineScope,
    private val maxCached: Int = 6
) {
    // Сбой перевода одной страницы не должен ронять родительский скоуп (экран, приложение)
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val gate = Mutex()
    private val cache = LinkedHashMap<PageKey, List<Block>>()
    private val jobs = HashMap<PageKey, Deferred<List<Block>>>()

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

    fun clearError() {
        _error.value = null
    }

    /** Запускает (или возвращает уже идущий) перевод страницы; готовые страницы отдаются из кэша. */
    fun request(key: PageKey, blocks: List<Block>, target: Language, source: Language?): Deferred<List<Block>> {
        cache[key]?.let { return CompletableDeferred(it) }
        jobs[key]?.let { return it }
        val job = work.async {
            try {
                gate.withLock { translatePage(key, blocks, target, source) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
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

    /** Отменяет переводы страниц, которые больше не нужны (пользователь ушёл далеко). */
    fun retainOnly(keep: Set<PageKey>) {
        jobs.filterKeys { it !in keep }.forEach { (key, job) ->
            job.cancel()
            jobs.remove(key)
        }
    }

    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cache.clear()
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

    private suspend fun translatePage(key: PageKey, blocks: List<Block>, target: Language, source: Language?): List<Block> {
        val pageLanguage = LanguageDetector.detect(blocks.joinToString("\n") { it.text })
        val alreadyInTarget = Languages.sameLanguage(pageLanguage, target.code)
        val result = arrayOfNulls<Block>(blocks.size)
        for ((i, block) in blocks.withIndex()) {
            result[i] = if (alreadyInTarget || !needsTranslation(block)) block else translateBlock(block, target, source)
            _progress.value = _progress.value + (key to result.toList())
        }
        val done = result.mapIndexed { i, b -> b ?: blocks[i] }
        cache[key] = done
        while (cache.size > maxCached) cache.remove(cache.keys.first())
        return done
    }

    private fun needsTranslation(block: Block) = block.type != BlockType.CODE && block.text.any { it.isLetter() }

    private suspend fun translateBlock(block: Block, target: Language, source: Language?): Block {
        val parts = splitForTranslation(block.text, MAX_CHUNK_CHARS).map { chunk ->
            while (holdCount > 0) released.await()
            _generating.value = true
            try {
                val raw = translateText(chunk, target, source).toList().joinToString("")
                SmartPipeline.stripThinking(raw).trim().ifBlank { chunk }
            } finally {
                _generating.value = false
            }
        }
        // Выделения привязаны к символам оригинала — в переводе они бессмысленны
        return Block(block.type, parts.joinToString(" "), block.level)
    }

    companion object {
        private const val MAX_CHUNK_CHARS = 1_200
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
