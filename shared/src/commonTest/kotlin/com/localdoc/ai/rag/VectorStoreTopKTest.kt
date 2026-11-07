package com.localdoc.ai.rag

import kotlin.test.Test
import kotlin.test.assertEquals

class VectorStoreTopKTest {
    @Test
    fun `top k keeps descending order without sorting all chunks`() {
        val store = VectorStore(MemoryStorage())
        store.put("doc", (0 until 100).map { i ->
            StoredChunk("chunk $i", i + 1, listOf(i / 99f, 1f - i / 99f))
        })
        val result = store.topK("doc", floatArrayOf(1f, 0f), 4)
        assertEquals(listOf(99, 98, 97, 96), result.map { it.section - 1 })
    }

    @Test
    fun `top k above chunk count returns every chunk`() {
        val store = VectorStore(MemoryStorage())
        store.put("doc", listOf(StoredChunk("one", 1, listOf(1f)), StoredChunk("two", 2, listOf(0.5f))))
        assertEquals(listOf(1, 2), store.topK("doc", floatArrayOf(1f), 5).map { it.section })
    }

    @Test
    fun `non positive k returns empty result`() {
        val store = VectorStore(MemoryStorage())
        store.put("doc", listOf(StoredChunk("one", 1, listOf(1f))))
        assertEquals(emptyList(), store.topK("doc", floatArrayOf(1f), 0))
        assertEquals(emptyList(), store.topK("doc", floatArrayOf(1f), -1))
    }

    @Test
    fun `mismatched dimensions cannot produce false matches`() {
        val store = VectorStore(MemoryStorage())
        store.put("doc", listOf(
            StoredChunk("short", 1, listOf(1f)),
            StoredChunk("matching", 2, listOf(0f, 1f)),
            StoredChunk("long", 3, listOf(1f, 0f, 1f))
        ))
        assertEquals(listOf(2), store.topK("doc", floatArrayOf(0f, 1f), 3).map { it.section })
        assertEquals(emptyList(), store.topK("doc", floatArrayOf(1f, 0f, 0f, 0f), 3))
    }

    private class MemoryStorage : RagStorage {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
}
