package com.localdoc.ai

import com.localdoc.ai.model.ExportedModel
import com.localdoc.ai.model.ModelExport
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelExportTest {
    private lateinit var root: File
    private lateinit var export: ModelExport

    @BeforeTest
    fun setUp() {
        root = kotlin.io.path.createTempDirectory("localdoc-models").toFile()
        export = ModelExport(root, listOf(ExportedModel("model-a", "Model A", "https://example/model.gguf", File(root, "model-a.gguf"))))
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `query lists only downloaded models`() {
        assertTrue(export.downloaded().isEmpty())
        File(root, "model-a.gguf").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(listOf("model-a"), export.downloaded().map { it.id })
        assertEquals(3L, export.resolve("model-a.gguf")?.file?.length())
    }

    @Test
    fun `unknown traversal and nested paths are rejected`() {
        File(root, "model-a.gguf").writeBytes(byteArrayOf(1))
        assertNull(export.resolve("unknown.gguf"))
        assertNull(export.resolve("../model-a.gguf"))
        assertNull(export.resolve("nested/model-a.gguf"))
        assertNull(export.resolve("model-a.gguf.part"))
    }

    @Test
    fun `file outside canonical model directory is rejected`() {
        val outside = kotlin.io.path.createTempDirectory("localdoc-outside").toFile()
        try {
            val file = File(outside, "outside.gguf").apply { writeBytes(byteArrayOf(1)) }
            val malicious = ModelExport(root, listOf(ExportedModel("outside", "Outside", "", file)))
            assertNull(malicious.resolve("outside.gguf"))
            assertTrue(malicious.downloaded().isEmpty())
        } finally {
            outside.deleteRecursively()
        }
    }
}
