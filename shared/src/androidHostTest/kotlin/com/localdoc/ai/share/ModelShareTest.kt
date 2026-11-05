package com.localdoc.ai.share

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelShareTest {

    private fun tempModelsDir(): File = createTempDirectory("modelshare").toFile()

    @Test
    fun `resolve returns the file for a downloaded model id`() {
        val dir = tempModelsDir()
        val file = File(dir, "gemma-3-1b-it-q4km.gguf")
        file.writeText("x")

        val resolved = ModelShare.resolve(dir, "gemma-3-1b-it-q4km")

        assertNotNull(resolved)
        assertEquals(file.canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun `resolve rejects empty id`() {
        assertNull(ModelShare.resolve(tempModelsDir(), ""))
        assertNull(ModelShare.resolve(tempModelsDir(), "   "))
    }

    @Test
    fun `resolve rejects path separators and traversal dots`() {
        val dir = tempModelsDir()
        assertNull(ModelShare.resolve(dir, "../secret"))
        assertNull(ModelShare.resolve(dir, ".."))
        assertNull(ModelShare.resolve(dir, "."))
        assertNull(ModelShare.resolve(dir, "a/b"))
        assertNull(ModelShare.resolve(dir, "a\\b"))
    }

    @Test
    fun `resolve rejects a model that is not downloaded`() {
        assertNull(ModelShare.resolve(tempModelsDir(), "no-such-model"))
    }

    @Test
    fun `resolve rejects a file escaping the models dir via symlink`() {
        val dir = tempModelsDir()
        val outside = tempModelsDir()
        val target = File(outside, "target.gguf").apply { writeText("x") }
        val link = File(dir, "escape.gguf")

        val created = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (e: Exception) {
            false // Windows без прав на симлинки — проверка недоступна
        }
        if (!created) return

        assertNull(ModelShare.resolve(dir, "escape"))
    }

    @Test
    fun `listDownloaded returns only gguf files sorted by name`() {
        val dir = tempModelsDir()
        File(dir, "b.gguf").writeText("b")
        File(dir, "a.gguf").writeText("a")
        File(dir, "notes.txt").writeText("n")
        File(dir, "c.gguf.part").writeText("p") // незавершённая загрузка — не модель

        val names = ModelShare.listDownloaded(dir).map { it.name }

        assertEquals(listOf("a.gguf", "b.gguf"), names)
    }

    @Test
    fun `listDownloaded returns empty for a missing dir`() {
        assertTrue(ModelShare.listDownloaded(File(tempModelsDir(), "nope")).isEmpty())
    }
}
