package com.localdoc.ai.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Встраивает в книгу внешние картинки (http/https) перед сохранением в файл: скачивает не больше
 * [maxImages] штук по [concurrency] одновременно, проверяет, что это картинка, и подставляет вместо адреса
 * идентификатор ресурса книги. Недоступные картинки пропускаются — книга всё равно сохраняется.
 * Обложка, заданная адресом, тоже встраивается.
 */
suspend fun Book.embedRemoteImages(
    maxImages: Int = 150,
    concurrency: Int = 2,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    fetch: suspend (String) -> ByteArray?
): Book {
    val urls = (blocks.mapNotNull { it.image } + listOfNotNull(cover)).filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        .distinct().take(maxImages)
    if (urls.isEmpty()) return this

    val collector = ImageCollector()
    val remap = HashMap<String, String?>()
    imageData.forEach { (id, bytes) -> remap[id] = collector.add(bytes) }
    val limit = Semaphore(concurrency.coerceAtLeast(1))
    var done = 0
    val downloaded: Map<String, ByteArray?> = coroutineScope {
        urls.map { url ->
            async {
                val bytes = limit.withPermit {
                    try {
                        fetch(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                done++
                onProgress(done, urls.size)
                url to bytes
            }
        }.awaitAll().toMap()
    }

    // Ссылки заменяются по порядку: общий лимит размера соблюдает сборщик
    val ids = HashMap<String, String?>()
    for (url in urls) ids[url] = downloaded[url]?.let(collector::add)

    fun resolve(ref: String?): String? = when {
        ref == null -> null
        ref in ids -> ids[ref]
        ref in remap -> remap[ref]
        else -> ref
    }
    val newBlocks = blocks.mapNotNull { block ->
        if (!block.isImage) return@mapNotNull block
        val ref = resolve(block.image)
        // Картинка не скачалась: остаётся подпись, если она есть
        when {
            ref != null -> block.copy(image = ref)
            block.text.isNotBlank() -> Block(BlockType.PARAGRAPH, block.text)
            else -> null
        }
    }
    // Удаление пустых блоков сдвигает индексы глав: пересчитываем
    val removed = blocks.indices.map { i -> newBlocks.size != blocks.size && blocks[i].isImage && resolve(blocks[i].image) == null && blocks[i].text.isBlank() }
    val shift = IntArray(blocks.size + 1)
    for (i in blocks.indices) shift[i + 1] = shift[i] + if (removed[i]) 1 else 0
    return copy(
        blocks = newBlocks,
        chapters = chapters.map { it.copy(startBlock = (it.startBlock - shift[it.startBlock.coerceIn(0, blocks.size)]).coerceAtLeast(0)) },
        cover = resolve(cover),
        images = collector.ids,
        imageData = collector.images
    )
}
