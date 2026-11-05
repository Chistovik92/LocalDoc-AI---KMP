package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KvTranslationStoreTest {

    private class FakeKv : StringKv {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val blocks = listOf(Block(BlockType.PARAGRAPH, "Привет, мир!"))

    @Test
    fun `saved translation is loaded back`() {
        val kv = FakeKv()
        val store = KvTranslationStore(kv)
        val key = PageKey("book:1", 3, "ru", "en", layout = 1_500)

        store.save(key, blocks)

        assertEquals(listOf("Привет, мир!"), store.load(key)?.map { it.text })
    }

    @Test
    fun `unknown page returns null`() {
        val store = KvTranslationStore(FakeKv())
        assertNull(store.load(PageKey("book:1", 0, "ru", null)))
    }

    @Test
    fun `clearDoc removes only that document`() {
        val kv = FakeKv()
        val store = KvTranslationStore(kv)
        val a = PageKey("book:1", 0, "ru", null)
        val b = PageKey("book:2", 0, "ru", null)

        store.save(a, blocks)
        store.save(b, blocks)

        store.clearDoc("book:1")

        assertNull(store.load(a), "перевод удалённой книги очищен")
        assertEquals(listOf("Привет, мир!"), store.load(b)?.map { it.text }, "другая книга не тронута")
    }

    @Test
    fun `corrupted value is ignored on load`() {
        val kv = FakeKv()
        kv.map["translation_v1:book:1:0:ru:0"] = "not-json"
        val store = KvTranslationStore(kv)

        assertNull(store.load(PageKey("book:1", 0, "ru", null)))
    }
}