package com.localdoc.ai.images

import androidx.compose.ui.graphics.ImageBitmap
import com.localdoc.ai.book.ImageFormat
import com.localdoc.ai.net.WebHttp
import com.localdoc.ai.net.nowMillis
import com.localdoc.ai.platform.FileAccess
import com.localdoc.ai.platform.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Ленивая загрузка картинок книг. Свои картинки (`im3`) читаются из хранилища книги, внешние
 * (http/https) скачиваются при первом показе и кэшируются на диске с ограничением общего размера:
 * при превышении [maxCacheBytes] самые давние по обращению удаляются. Один файл — не больше
 * [WebHttp.MAX_IMAGE_BYTES]. Декодированные картинки хранятся в памяти не дольше лимита [maxBitmapBytes].
 */
class ImageLoader(
    private val files: FileAccess,
    private val kv: KeyValueStore,
    private val http: () -> WebHttp,
    private val maxCacheBytes: Long = 160L * 1024 * 1024,
    private val maxBitmapBytes: Long = 48L * 1024 * 1024,
    private val now: () -> Long = ::nowMillis
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer()))
    private val lock = Mutex()
    private val failed = HashSet<String>()

    /** name → {size, used}; хранится в KV, файлы лежат в `rc/`. */
    private var index: MutableMap<String, Map<String, Long>> =
        (kv.get(KEY)?.let { runCatching { json.decodeFromString(indexSerializer, it) }.getOrNull() } ?: emptyMap()).toMutableMap()

    // Память: LRU по порядку обращения (LinkedHashMap, запись переставляется в конец при каждом обращении)
    private val bitmaps = LinkedHashMap<String, Pair<ImageBitmap, Long>>()
    private var bitmapBytes = 0L

    /** Исходные байты картинки либо null (нет файла, нет сети, не картинка). */
    suspend fun bytes(entryId: String?, ref: String, referer: String? = null): ByteArray? {
        if (!ref.startsWith("http://", true) && !ref.startsWith("https://", true)) {
            return entryId?.let { files.readAppFile(localName(it, ref)) }
        }
        val name = cacheName(ref)
        lock.withLock {
            files.readAppFile(name)?.let { cached ->
                touch(name, cached.size.toLong())
                return cached
            }
            if (ref in failed) return null
        }
        return try {
            val headers = HashMap<String, String>()
            headers["Accept"] = "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8"
            referer?.let { headers["Referer"] = it }
            val result = http().get(ref, headers, WebHttp.MAX_IMAGE_BYTES)
            if (!result.isOk || ImageFormat.mimeOf(result.body) == null) {
                lock.withLock { failed.add(ref) }
                null
            } else {
                lock.withLock {
                    files.writeAppFile(name, result.body)
                    touch(name, result.body.size.toLong())
                    evictIfNeeded()
                }
                result.body
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Нет сети — неудачу не запоминаем: при следующем показе попробуем снова
            null
        }
    }

    /** Декодированная картинка, уменьшенная до [maxDimension] по большей стороне. */
    suspend fun bitmap(entryId: String?, ref: String, maxDimension: Int, referer: String? = null): ImageBitmap? =
        withContext(Dispatchers.Default) { loadBitmap(entryId, ref, maxDimension, referer) }

    private suspend fun loadBitmap(entryId: String?, ref: String, maxDimension: Int, referer: String?): ImageBitmap? {
        val key = "${entryId.orEmpty()}|$ref|$maxDimension"
        lock.withLock {
            bitmaps.remove(key)?.let { hit ->
                bitmaps[key] = hit
                return hit.first
            }
        }
        val data = bytes(entryId, ref, referer) ?: return null
        val decoded = decodeImageBitmap(data, maxDimension) ?: return null
        val cost = decoded.width.toLong() * decoded.height * 4
        lock.withLock {
            bitmaps[key] = decoded to cost
            bitmapBytes += cost
            val iterator = bitmaps.entries.iterator()
            while (bitmapBytes > maxBitmapBytes && iterator.hasNext() && bitmaps.size > 1) {
                val oldest = iterator.next()
                if (oldest.key == key) continue
                bitmapBytes -= oldest.value.second
                iterator.remove()
            }
        }
        return decoded
    }

    /** Удаляет весь дисковый кэш внешних картинок («Очистить кэш» в настройках). */
    suspend fun clearCache() = lock.withLock {
        files.deleteAppFiles(CACHE_DIR)
        index = HashMap()
        failed.clear()
        bitmaps.clear()
        bitmapBytes = 0
        kv.remove(KEY)
    }

    fun cacheBytes(): Long = index.values.sumOf { it["size"] ?: 0L }

    private fun touch(name: String, size: Long) {
        index[name] = mapOf("size" to size, "used" to now())
        kv.put(KEY, json.encodeToString(indexSerializer, index))
    }

    private fun evictIfNeeded() {
        var total = index.values.sumOf { it["size"] ?: 0L }
        if (total <= maxCacheBytes) return
        val target = maxCacheBytes * 8 / 10
        for ((name, meta) in index.entries.sortedBy { it.value["used"] ?: 0L }) {
            if (total <= target) break
            files.deleteAppFile(name)
            total -= meta["size"] ?: 0L
            index.remove(name)
        }
        kv.put(KEY, json.encodeToString(indexSerializer, index))
    }

    companion object {
        private const val KEY = "image_cache_v1"
        const val CACHE_DIR = "rc/"

        fun localName(entryId: String, imageId: String) = "$entryId.img.$imageId"

        /** Имя файла в кэше: 64-битный FNV-1a адреса. */
        fun cacheName(url: String): String {
            var h = -0x340d631b7bdddcdbL
            for (b in url.encodeToByteArray()) {
                h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
            }
            return CACHE_DIR + h.toULong().toString(16).padStart(16, '0')
        }
    }
}
