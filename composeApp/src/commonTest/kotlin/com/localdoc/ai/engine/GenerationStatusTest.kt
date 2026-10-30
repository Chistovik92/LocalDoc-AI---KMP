package com.localdoc.ai.engine

import com.localdoc.ai.TestStrings
import com.localdoc.ai.i18n.L10n
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenerationStatusTest {

    @BeforeTest
    fun strings() = TestStrings.install()

    @AfterTest
    fun resetStrings() {
        L10n.testResolver = null
    }

    @Test
    fun okDoesNotThrow() = runTest {
        GenerationStatus.ensureOk(GenerationStatus.OK)
    }

    @Test
    fun promptTooLongProducesUserFacingMessage() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            GenerationStatus.ensureOk(GenerationStatus.ERR_PROMPT_TOO_LONG)
        }
        assertEquals(GenerationStatus.message(GenerationStatus.ERR_PROMPT_TOO_LONG), error.message)
    }

    @Test
    fun unknownCodeIsReported() = runTest {
        assertEquals("Неизвестная ошибка генерации (-42)", GenerationStatus.message(-42))
    }
}
