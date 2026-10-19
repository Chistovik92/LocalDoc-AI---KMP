package com.localdoc.ai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenerationStatusTest {

    @Test
    fun okDoesNotThrow() {
        GenerationStatus.ensureOk(GenerationStatus.OK)
    }

    @Test
    fun promptTooLongProducesUserFacingMessage() {
        val error = assertFailsWith<IllegalStateException> {
            GenerationStatus.ensureOk(GenerationStatus.ERR_PROMPT_TOO_LONG)
        }
        assertEquals(GenerationStatus.message(GenerationStatus.ERR_PROMPT_TOO_LONG), error.message)
    }

    @Test
    fun unknownCodeIsReported() {
        assertEquals("Неизвестная ошибка генерации (-42)", GenerationStatus.message(-42))
    }
}
