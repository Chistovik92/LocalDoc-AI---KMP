package com.localdoc.ai.search

import com.localdoc.ai.domain.NovelFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Разбиение текста на слова для индекса и запроса: буквы и цифры, нижний регистр, «ё» = «е». */
object SearchTokenizer {

    private const val MIN_TOKEN = 2

    fun tokens(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= MIN_TOKEN) result.add(current.toString())
            current.clear()
        }
        for (ch in text) {
            if (ch.isLetterOrDigit()) current.append(normalize(ch)) else flush()
        }
        flush()
        return result
    }

    private fun normalize(ch: Char): Char = when (val lower = ch.lowercaseChar()) {
        'ё' -> 'е'
        else -> lower
    }

    /**
     * Основа слова запроса для поиска по началу слова: у слов от четырёх букв отбрасываются 1–2 последние,
     * поэтому «книга» находит «книги», «книгой», «книгах». Короткие слова ищутся точно.
     */
    fun stem(token: String): String = when {
        token.length >= 7 -> token.dropLast(2)
        token.length >= 4 -> token.dropLast(1)
        else -> token
    }
}

/** Найденное место: документ, единица индекса (блок книги) и ссылка на элемент (для сводных документов, например каталога). */
data class IndexHit(val docId: String, val unit: Int, val ref: String?)

@Serializable
private class Shard(
    val signature: String,
    /** Слово → номера единиц, отсортированные и записанные разностями через запятую. */
    val terms: Map<String, String>,
    /** Для сводных документов (каталог): единица i — элемент refs[i]. */
    val refs: List<String> = emptyList()
)

/**
 * Полнотекстовый индекс на диске: на каждый документ (книгу) свой файл с обратным индексом «слово → блоки».
 * Обновление инкрементальное: файл пересобирается только у изменившейся книги (по подписи `signature`),
 * у удалённой — стирается.
 *
 * Чтобы поиск по тысячам книг не читал шард каждой книги с диска, поверх файлов-шардов ведётся общий словарь
 * слов «слово → документы» ([DICT_FILE]). Запрос сначала находит по нему книги-кандидаты, и лишь их шарды
 * читаются для точных номеров блоков. Словарь обновляется инкрементально вместе с шардами; устаревшие записи
 * безопасны — они лишь приводят к лишнему чтению, а результат фильтруется по факту.
 *
 * Манифест (какие документы проиндексированы и с какой подписью) хранится строкой через [loadManifest]/[saveManifest].
 */
class FullTextIndex(
    private val files: NovelFiles,
    private val loadManifest: () -> String?,
    private val saveManifest: (String) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val manifestSerializer = MapSerializer(String.serializer(), String.serializer())
    private val dictSerializer = MapSerializer(String.serializer(), String.serializer())
    private var manifest: MutableMap<String, String> = readManifest()
    private val cache = LinkedHashMap<String, Shard>()

    private var batchDepth = 0
    private var manifestDirty = false
    private var dictFileDirty = false

    // --- Общий словарь слов «слово → документы» ---
    private var dictMap: MutableMap<String, MutableSet<String>>? = null
    private var sortedTermsCache: List<String> = emptyList()
    private var sortedDirty = true

    /**
     * Группа изменений с одной записью манифеста в конце: без неё каждая из тысяч книг сериализовала бы весь
     * манифест заново (квадратичный рост). Обрыв посреди группы безопасен: файлы индекса без записи в манифесте
     * при следующей синхронизации просто строятся заново.
     */
    suspend fun <T> batch(block: suspend () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0) {
                if (manifestDirty) flushManifest()
                if (dictFileDirty) flushDict()
            }
        }
    }

    /** Запись после индексации: внутри [batch] откладывается до его конца. */
    private fun persistManifest() {
        if (batchDepth > 0) manifestDirty = true else flushManifest()
    }

    /**
     * Немедленная запись. Нужна удалению и инвалидации: если процесс оборвётся до конца группы, отложенная запись
     * потеряла бы «книга изменилась», и устаревший индекс считался бы актуальным (подпись книги не меняется).
     */
    private fun flushManifest() {
        manifestDirty = false
        saveManifest(json.encodeToString(manifestSerializer, manifest))
    }

    fun indexedDocs(): Set<String> = manifest.keys.toSet()

    fun isCurrent(docId: String, signature: String): Boolean = manifest[docId] == signature

    /** Индексирует документ, если подпись изменилась; true — индекс обновлён. [units] — тексты блоков по порядку. */
    suspend fun index(docId: String, signature: String, units: List<String>, refs: List<String> = emptyList()): Boolean {
        if (isCurrent(docId, signature) && files.read(fileName(docId)) != null) return false
        val oldTerms = shardTerms(docId)
        val postings = HashMap<String, MutableList<Int>>()
        units.forEachIndexed { unit, text ->
            for (token in SearchTokenizer.tokens(text).toSet()) postings.getOrPut(token) { ArrayList() }.add(unit)
            if (unit % YIELD_EVERY == 0) yield()
        }
        val shard = Shard(signature, postings.mapValues { encode(it.value) }, refs)
        files.write(fileName(docId), json.encodeToString(Shard.serializer(), shard).encodeToByteArray())
        cache[docId] = shard
        trimCache()
        // Общий словарь: ушедшие слова удаляются, новые добавляются (общие остаются)
        val dict = ensureDict()
        val newTerms = postings.keys
        for (term in oldTerms.orEmpty()) if (term !in newTerms) dictRemove(dict, term, docId)
        for (term in newTerms) if (term !in oldTerms.orEmpty()) dictAdd(dict, term, docId)
        persistDict()
        manifest[docId] = signature
        persistManifest()
        return true
    }

    fun remove(docId: String) {
        val terms = shardTerms(docId)
        files.deleteAll(fileName(docId))
        cache.remove(docId)
        removeFromDict(docId, terms)
        if (manifest.remove(docId) != null) flushManifest()
    }

    /** Забывает документ: при следующей синхронизации он будет проиндексирован заново. */
    fun invalidate(docId: String) {
        val terms = shardTerms(docId)
        removeFromDict(docId, terms)
        if (manifest.remove(docId) != null) flushManifest()
        cache.remove(docId)
    }

    /** Стирает весь индекс (после восстановления из резервной копии книги другие, индекс строится заново). */
    fun clear() {
        manifest.keys.toList().forEach { files.deleteAll(fileName(it)) }
        manifest.clear()
        cache.clear()
        files.deleteAll(DICT_FILE)
        dictMap = null
        sortedDirty = true
        sortedTermsCache = emptyList()
        dictFileDirty = false
        flushManifest()
    }

    /**
     * Ищет блоки, где есть все слова запроса (по началу слова, см. [SearchTokenizer.stem]). Результаты — по документам
     * в порядке числа совпавших блоков; в документе не больше [perDoc] мест, всего не больше [limit].
     * Книги-кандидаты определяются по общему словарю — шарды остальных книг с диска не читаются.
     */
    suspend fun search(query: String, docs: Collection<String> = indexedDocs(), perDoc: Int = 3, limit: Int = 60): List<IndexHit> {
        val stems = SearchTokenizer.tokens(query).map(SearchTokenizer::stem).distinct()
        if (stems.isEmpty()) return emptyList()
        val candidates = candidateDocs(stems, docs)
        val found = ArrayList<Pair<Int, List<IndexHit>>>()
        for (docId in candidates) {
            val shard = shard(docId) ?: continue
            val units = matchingUnits(shard, stems)
            if (units.isEmpty()) continue
            found.add(units.size to units.take(perDoc).map { IndexHit(docId, it, shard.refs.getOrNull(it)) })
            yield()
        }
        return found.sortedByDescending { it.first }.flatMap { it.second }.take(limit)
    }

    /** Книги, где могут встретиться все слова запроса: пересечение документов по каждому слову через общий словарь. */
    private fun candidateDocs(stems: List<String>, docs: Collection<String>): List<String> {
        val dict = ensureDict()
        if (dict.isEmpty()) return emptyList()
        val allowed = docs.toSet()
        var result: Set<String>? = null
        for (stem in stems) {
            val matched = HashSet<String>()
            val terms = sortedTerms()
            val from = lowerBound(terms, stem)
            val to = lowerBound(terms, stem + '\uFFFF')
            for (i in from until to) dict[terms[i]]?.forEach { if (it in allowed) matched.add(it) }
            result = result?.intersect(matched) ?: matched
            if (result.isEmpty()) return emptyList()
        }
        return result.orEmpty().toList()
    }

    private fun matchingUnits(shard: Shard, stems: List<String>): List<Int> {
        var result: Set<Int>? = null
        for (stem in stems) {
            val units = HashSet<Int>()
            for ((term, postings) in shard.terms) if (term.startsWith(stem)) units.addAll(decode(postings))
            result = result?.intersect(units) ?: units
            if (result.isEmpty()) return emptyList()
        }
        return result.orEmpty().sorted()
    }

    private fun shard(docId: String): Shard? {
        cache.remove(docId)?.let { cache[docId] = it; return it }
        val bytes = files.read(fileName(docId)) ?: return null
        val shard = try {
            json.decodeFromString(Shard.serializer(), bytes.decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Повреждённый файл индекса: документ будет проиндексирован заново
            invalidate(docId)
            return null
        }
        cache[docId] = shard
        trimCache()
        return shard
    }

    /** Слова документа по шарду в кэше или на диске; null — файла нет или он повреждён (без побочных эффектов). */
    private fun shardTerms(docId: String): Set<String>? {
        cache[docId]?.let { return it.terms.keys }
        val bytes = files.read(fileName(docId)) ?: return null
        return runCatching { json.decodeFromString(Shard.serializer(), bytes.decodeToString()) }.getOrNull()?.terms?.keys
    }

    private fun trimCache() {
        while (cache.size > CACHED_SHARDS) cache.remove(cache.keys.first())
    }

    // --- Общий словарь слов ---

    private fun ensureDict(): MutableMap<String, MutableSet<String>> {
        dictMap?.let { return it }
        val loaded: Map<String, String> = files.read(DICT_FILE)?.let { bytes ->
            runCatching { json.decodeFromString(dictSerializer, bytes.decodeToString()) }.getOrNull()
        }.orEmpty()
        val map = HashMap<String, MutableSet<String>>()
        for ((term, docs) in loaded) if (docs.isNotEmpty()) map[term] = docs.split(',').toCollection(HashSet())
        dictMap = map
        sortedDirty = true
        return map
    }

    private fun sortedTerms(): List<String> {
        if (sortedDirty) {
            sortedTermsCache = dictMap?.keys.orEmpty().sorted()
            sortedDirty = false
        }
        return sortedTermsCache
    }

    private fun dictAdd(dict: MutableMap<String, MutableSet<String>>, term: String, docId: String) {
        val set = dict[term]
        if (set == null) {
            dict[term] = HashSet<String>().apply { add(docId) }
            sortedDirty = true
        } else {
            set.add(docId)
        }
    }

    private fun dictRemove(dict: MutableMap<String, MutableSet<String>>, term: String, docId: String) {
        val set = dict[term] ?: return
        if (!set.remove(docId)) return
        if (set.isEmpty()) {
            dict.remove(term)
            sortedDirty = true
        }
    }

    private fun removeFromDict(docId: String, terms: Collection<String>?) {
        if (terms.isNullOrEmpty()) return
        val dict = ensureDict()
        for (term in terms) dictRemove(dict, term, docId)
        persistDict()
    }

    private fun persistDict() {
        if (batchDepth > 0) dictFileDirty = true else flushDict()
    }

    private fun flushDict() {
        dictFileDirty = false
        val map = dictMap ?: return
        val serialized = HashMap<String, String>(map.size)
        for ((term, docs) in map) serialized[term] = docs.sorted().joinToString(",")
        files.write(DICT_FILE, json.encodeToString(dictSerializer, serialized).encodeToByteArray())
    }

    /** Первый индекс, начиная с которого элемент не меньше [key]. */
    private fun lowerBound(list: List<String>, key: String): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun readManifest(): MutableMap<String, String> =
        loadManifest()?.let { runCatching { json.decodeFromString(manifestSerializer, it) }.getOrNull() }?.toMutableMap() ?: HashMap()

    private fun fileName(docId: String) = "idx.$docId.json"

    private fun encode(units: List<Int>): String {
        var previous = 0
        return units.joinToString(",") { (it - previous).also { _ -> previous = it }.toString() }
    }

    private fun decode(postings: String): List<Int> {
        var acc = 0
        return postings.split(',').mapNotNull { it.toIntOrNull() }.map { acc += it; acc }
    }

    private companion object {
        const val YIELD_EVERY = 200
        const val CACHED_SHARDS = 6
        const val DICT_FILE = "idx.dict.json"
    }
}

/** Фрагмент текста вокруг первого найденного слова — для показа в результатах поиска. */
object Snippets {

    private const val RADIUS = 70

    fun around(text: String, query: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val stems = SearchTokenizer.tokens(query).map(SearchTokenizer::stem)
        val lower = clean.lowercase().replace('ё', 'е')
        val at = stems.mapNotNull { stem -> lower.indexOf(stem).takeIf { it >= 0 } }.minOrNull() ?: 0
        val from = (at - RADIUS).coerceAtLeast(0)
        val to = (at + RADIUS).coerceAtMost(clean.length)
        return (if (from > 0) "…" else "") + clean.substring(from, to).trim() + (if (to < clean.length) "…" else "")
    }
}
