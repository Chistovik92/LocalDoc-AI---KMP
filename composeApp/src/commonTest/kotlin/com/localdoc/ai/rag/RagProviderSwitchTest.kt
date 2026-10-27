package com.localdoc.ai.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagProviderSwitchTest {

    private val storage = MemoryRagStorage()
    private val store = VectorStore(storage)
    private val text = (1..600).joinToString(" ") { "слово$it" }

    private fun repo(source: () -> EmbeddingProvider, onFailure: (Throwable) -> Unit = {}) =
        RagRepository(store, source, onFailure)

    @Test
    fun switchingProviderReindexesWithoutLosingText() {
        val a = FakeEmbedder("a", 3)
        val b = FakeEmbedder("b", 2)
        var current: EmbeddingProvider = a
        val rag = repo({ current })

        val count = rag.ensureIndexed("doc", text)
        val textsBefore = store.get("doc").map { it.text }
        assertTrue(store.get("doc").all { it.vector.size == 3 })

        current = b
        assertEquals(count, rag.ensureIndexed("doc", text))

        assertEquals(textsBefore, store.get("doc").map { it.text })
        assertTrue(store.get("doc").all { it.vector.size == 2 })
        assertEquals("b-2", store.providerOf("doc"))
    }

    @Test
    fun sameProviderDoesNotReindex() {
        val a = FakeEmbedder("a", 3)
        val rag = repo({ a })
        rag.ensureIndexed("doc", text)
        val encoded = a.passages.size
        rag.ensureIndexed("doc", text)
        assertEquals(encoded, a.passages.size)
    }

    @Test
    fun retrievalFromForeignIndexIsEmptyNotGarbage() {
        val a = FakeEmbedder("a", 3)
        val b = FakeEmbedder("b", 2)
        var current: EmbeddingProvider = a
        val rag = repo({ current })
        rag.ensureIndexed("doc", text)

        current = b
        assertTrue(rag.retrieveTopK("doc", "вопрос").isEmpty())
        // индекс при этом цел и ждёт пересчёта
        assertTrue(store.get("doc").isNotEmpty())
        assertTrue(store.get("doc").all { it.vector.size == 3 })
    }

    @Test
    fun queryAndPassagesUseTheirOwnEncoders() {
        val a = FakeEmbedder("a", 3)
        val rag = repo({ a })
        rag.ensureIndexed("doc", "один два три")
        rag.retrieveTopK("doc", "вопрос")
        assertEquals(listOf("вопрос"), a.queries)
        assertEquals(listOf("один два три"), a.passages)
    }

    @Test
    fun failedReindexKeepsOldIndex() {
        val a = FakeEmbedder("a", 3)
        var broken = false
        val b = FakeEmbedder("b", 2, failing = { broken })
        var current: EmbeddingProvider = a
        val rag = repo({ current })
        rag.ensureIndexed("doc", text)
        val before = store.get("doc")

        current = b
        broken = true
        runCatching { rag.indexDocument("doc", text) }
        assertEquals(before, store.get("doc"))
        assertEquals("a-3", store.providerOf("doc"))
    }

    @Test
    fun failureFallsBackToNextProvider() {
        val broken = FakeEmbedder("onnx", 2, failing = { true })
        val fallback = FakeEmbedder("hash", 3)
        var current: EmbeddingProvider = broken
        val failures = mutableListOf<Throwable>()
        val rag = repo({ current }, onFailure = { failures += it; current = fallback })

        val count = rag.ensureIndexed("doc", text)

        assertTrue(count > 0)
        assertEquals(1, failures.size)
        assertEquals("hash-3", store.providerOf("doc"))
        assertTrue(rag.retrieveTopK("doc", "вопрос").isNotEmpty())
    }

    @Test
    fun legacyHashingIndexIsReusedByHashingEmbedder() {
        val hashing = HashingEmbedder()
        // Индекс предыдущей версии: чанки, посчитанные хешированием, без метаданных
        val chunks = TextChunker.chunk(text).mapIndexed { i, t -> StoredChunk(t, i + 1, hashing.encode(t).toList()) }
        VectorStore(storage).put("doc", chunks, hashing.signature)
        storage.remove("rag-meta:doc")

        val encodedNothing = FakeEmbedder("spy", 384)
        val rag = repo({ hashing })
        assertEquals(chunks.size, rag.ensureIndexed("doc", text))
        assertEquals(chunks, store.get("doc"))
        assertTrue(encodedNothing.passages.isEmpty())
    }
}
