package com.localdoc.ai.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorStoreVersionTest {

    private val storage = MemoryRagStorage()
    private val store = VectorStore(storage)
    private val chunk = StoredChunk("text", 1, listOf(1f, 0f))

    @Test
    fun legacyIndexWithoutMetaIsReadableAsHashing() {
        // Так индекс лежал до версии 2.0: чанки и список документов, без метаданных
        storage.put("rag:old", """[{"text":"старый","section":1,"vector":[0.6,0.8]}]""")
        storage.put("rag-index:doc-ids", """["old"]""")

        assertEquals(1, store.get("old").size)
        assertEquals(VectorStore.LEGACY_PROVIDER, store.providerOf("old"))
        assertTrue(store.isCurrent("old", "hash-384"))
        assertFalse(store.isCurrent("old", "e5-small-384"))
    }

    @Test
    fun putRecordsProviderAndFormat() {
        store.put("doc", listOf(chunk), "e5-small-384")
        assertEquals("e5-small-384", store.providerOf("doc"))
        assertTrue(store.isCurrent("doc", "e5-small-384"))
        assertFalse(store.isCurrent("doc", "hash-384"))
    }

    @Test
    fun missingDocumentIsNeverCurrent() {
        assertNull(store.providerOf("nope"))
        assertFalse(store.isCurrent("nope", "hash-384"))
    }

    @Test
    fun tooOldFormatIsNotCurrent() {
        store.put("doc", listOf(chunk), "hash-384")
        storage.put("rag-meta:doc", """{"format":0,"provider":"hash-384"}""")
        assertFalse(store.isCurrent("doc", "hash-384"))
    }

    @Test
    fun brokenMetaFallsBackToLegacy() {
        store.put("doc", listOf(chunk), "e5-small-384")
        storage.put("rag-meta:doc", "{broken")
        assertEquals(VectorStore.LEGACY_PROVIDER, store.providerOf("doc"))
    }

    @Test
    fun removeDeletesMeta() {
        store.put("doc", listOf(chunk), "e5-small-384")
        store.remove("doc")
        assertNull(storage.get("rag-meta:doc"))
        assertNull(store.providerOf("doc"))
    }

    @Test
    fun evictionDeletesMetaOfEvictedDocuments() {
        (1..6).forEach { store.put("d$it", listOf(chunk), "hash-384") }
        assertNull(storage.get("rag:d1"))
        assertNull(storage.get("rag-meta:d1"))
        assertEquals("hash-384", store.providerOf("d6"))
    }

    @Test
    fun overwritingReplacesChunksAndProvider() {
        store.put("doc", listOf(chunk), "hash-384")
        store.put("doc", listOf(chunk, chunk), "e5-small-384")
        assertEquals(2, store.get("doc").size)
        assertEquals("e5-small-384", store.providerOf("doc"))
    }
}
