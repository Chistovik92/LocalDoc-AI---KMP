package com.localdoc.ai.search

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.catalog.CatalogItem

/** Место в тексте книги, найденное по запросу. */
data class BookHit(val entry: BookEntry, val block: Int, val snippet: String)

data class SearchResults(val books: List<BookHit>, val catalog: List<CatalogItem>) {
    val isEmpty: Boolean get() = books.isEmpty() && catalog.isEmpty()
}

/**
 * Поиск по тексту всех книг библиотеки и по каталогу устройства поверх [FullTextIndex]. Источники передаются
 * функциями: общий код не зависит от платформенных хранилищ, а тесты подставляют свои данные.
 */
class LibrarySearch(
    private val index: FullTextIndex,
    private val books: () -> List<BookEntry>,
    /** Тексты блоков книги по порядку; null — книги нет. */
    private val loadBlocks: suspend (entryId: String) -> List<String>?,
    private val catalogItems: () -> List<CatalogItem>
) {

    /**
     * Приводит индекс в соответствие с библиотекой и каталогом: новые и изменённые книги индексируются, удалённые
     * стираются. Книга с неизменной подписью не читается с диска. Возвращает число обновлённых документов.
     */
    suspend fun sync(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val entries = books()
        val wanted = entries.associateBy { libDoc(it.id) }
        index.indexedDocs().filter { it.startsWith(LIB_PREFIX) && it !in wanted }.forEach(index::remove)
        var updated = 0
        entries.forEachIndexed { i, entry ->
            val doc = libDoc(entry.id)
            val signature = signatureOf(entry)
            if (!index.isCurrent(doc, signature)) {
                val units = loadBlocks(entry.id)
                if (units != null && index.index(doc, signature, units)) updated++
            }
            onProgress(i + 1, entries.size + 1)
        }
        if (syncCatalog()) updated++
        onProgress(entries.size + 1, entries.size + 1)
        return updated
    }

    /** Каталог индексируется одним документом: единица — файл, ссылка — его адрес. */
    private suspend fun syncCatalog(): Boolean {
        val items = catalogItems()
        if (items.isEmpty()) {
            if (CATALOG_DOC in index.indexedDocs()) index.remove(CATALOG_DOC)
            return false
        }
        val signature = catalogSignature(items)
        if (index.isCurrent(CATALOG_DOC, signature)) return false
        val units = items.map { listOf(it.title, it.author, it.series, it.genres.joinToString(" "), it.name, it.folder, it.year?.toString().orEmpty()).joinToString(" ") }
        return index.index(CATALOG_DOC, signature, units, items.map { it.uri })
    }

    /** Книга изменилась (повторный импорт): её индекс устарел. */
    fun invalidate(entryId: String) = index.invalidate(libDoc(entryId))

    suspend fun search(query: String, perBook: Int = 3, limit: Int = 60): SearchResults {
        if (SearchTokenizer.tokens(query).isEmpty()) return SearchResults(emptyList(), emptyList())
        val byId = books().associateBy { libDoc(it.id) }
        val hits = index.search(query, index.indexedDocs() + CATALOG_DOC, perBook, limit)
        val items = catalogItems().associateBy { it.uri }
        val catalog = hits.filter { it.docId == CATALOG_DOC }.mapNotNull { hit -> hit.ref?.let(items::get) }

        val bookHits = ArrayList<BookHit>()
        for ((doc, docHits) in hits.filter { it.docId != CATALOG_DOC }.groupBy { it.docId }) {
            val entry = byId[doc] ?: continue
            val blocks = loadBlocks(entry.id) ?: continue
            docHits.forEach { hit -> blocks.getOrNull(hit.unit)?.let { bookHits.add(BookHit(entry, hit.unit, Snippets.around(it, query))) } }
        }
        return SearchResults(bookHits, catalog)
    }

    private fun libDoc(entryId: String) = LIB_PREFIX + entryId

    private fun signatureOf(entry: BookEntry) = "${entry.totalBlocks}:${entry.title}:${entry.author}"

    private fun catalogSignature(items: List<CatalogItem>): String {
        var hash = FNV_OFFSET
        for (item in items) {
            for (ch in "${item.uri}|${item.modified}|${item.title}|${item.author}\n") hash = (hash xor ch.code.toLong()) * FNV_PRIME
        }
        return "${items.size}:${hash.toULong().toString(16)}"
    }

    companion object {
        const val LIB_PREFIX = "lib."
        const val CATALOG_DOC = "catalog"
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }
}
