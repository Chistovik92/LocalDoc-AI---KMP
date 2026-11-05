package com.localdoc.ai.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncMergeTest {
    private fun rec(id: String, at: Long, device: String = "a", data: String = "x", deleted: Boolean = false) =
        SyncRecord(id, at, device, deleted, data)

    private fun map(vararg r: SyncRecord) = r.associateBy { it.id }

    @Test
    fun `last edit wins per record and both sides keep their own other records`() {
        val base = map(rec("p/1", 1, data = "0"), rec("p/2", 1, data = "0"))
        val local = map(rec("p/1", 5, "a", "L"), rec("p/2", 1, data = "0"))
        val remote = map(rec("p/1", 1, data = "0"), rec("p/2", 7, "b", "R"))
        val r = SyncMerge.merge(local, remote, base, now = 10)
        assertEquals("L", r.merged.getValue("p/1").data)
        assertEquals("R", r.merged.getValue("p/2").data)
        assertTrue(r.pushNeeded)
        assertEquals(listOf("p/2"), r.toLocal.map { it.id })
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun `independent edits of one record are a conflict and the later one wins`() {
        val base = map(rec("p/1", 1, data = "0"))
        val r = SyncMerge.merge(map(rec("p/1", 5, "a", "L")), map(rec("p/1", 9, "b", "R")), base, 10)
        assertEquals(1, r.conflicts.size)
        assertEquals("R", r.merged.getValue("p/1").data)
    }

    @Test
    fun `equal timestamps are resolved by device id the same way on both sides`() {
        val a = rec("p/1", 5, "a", "A")
        val b = rec("p/1", 5, "b", "B")
        val one = SyncMerge.merge(map(a), map(b), emptyMap(), 10).merged.getValue("p/1")
        val two = SyncMerge.merge(map(b), map(a), emptyMap(), 10).merged.getValue("p/1")
        assertEquals(one, two)
        assertEquals("B", one.data)
    }

    @Test
    fun `a newer delete beats an older edit and an older delete loses to a newer edit`() {
        val del = rec("p/1", 8, "a", deleted = true)
        assertTrue(SyncMerge.merge(map(del), map(rec("p/1", 3, "b", "E")), emptyMap(), 10).merged.getValue("p/1").deleted)
        assertFalse(SyncMerge.merge(map(rec("p/1", 3, "a", deleted = true)), map(rec("p/1", 8, "b", "E")), emptyMap(), 10).merged.getValue("p/1").deleted)
    }

    @Test
    fun `identical content needs no push and no local change`() {
        val r = SyncMerge.merge(map(rec("p/1", 5, "a", "same")), map(rec("p/1", 6, "b", "same")), emptyMap(), 10)
        assertFalse(r.pushNeeded)
        assertTrue(r.toLocal.isEmpty())
    }

    @Test
    fun `old tombstones are forgotten`() {
        val old = rec("p/1", 1, deleted = true)
        val r = SyncMerge.merge(map(old), map(old), emptyMap(), SyncFormat.TOMBSTONE_TTL_MS + 100)
        assertTrue(r.merged.isEmpty())
        assertTrue(r.pushNeeded)
    }
}
