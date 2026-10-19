package com.localdoc.ai.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentRangeTest {

    @Test
    fun unsatisfiedRangeGivesTotalSize() {
        assertEquals(1_193_000_000L, parseTotalFromContentRange("bytes */1193000000"))
    }

    @Test
    fun partialRangeGivesTotalSize() {
        assertEquals(5000L, parseTotalFromContentRange("bytes 100-4999/5000"))
    }

    @Test
    fun unknownOrMissingTotalIsNull() {
        assertNull(parseTotalFromContentRange("bytes 0-99/*"))
        assertNull(parseTotalFromContentRange(null))
        assertNull(parseTotalFromContentRange("garbage"))
    }
}
