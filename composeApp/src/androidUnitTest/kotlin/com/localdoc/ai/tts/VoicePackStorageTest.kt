package com.localdoc.ai.tts

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoicePackStorageTest {

    private val work: File = Files.createTempDirectory("voices").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    private fun bz2(bytes: ByteArray): File {
        val file = File(work, "voice.tar.bz2")
        BZip2CompressorOutputStream(file.outputStream()).use { it.write(bytes) }
        return file
    }

    @Test
    fun `tar bz2 voice pack is unpacked into the voice folder`() {
        val model = ByteArray(300_000) { (it * 31 % 256).toByte() }
        val archive = bz2(
            buildTar(
                listOf(
                    TarEntry("vits-piper-ru_RU-denis-medium/", type = '5'),
                    TarEntry("vits-piper-ru_RU-denis-medium/ru_RU-denis-medium.onnx", model),
                    TarEntry("vits-piper-ru_RU-denis-medium/tokens.txt", "a 1".toByteArray()),
                    TarEntry("vits-piper-ru_RU-denis-medium/espeak-ng-data/", type = '5'),
                    TarEntry("vits-piper-ru_RU-denis-medium/espeak-ng-data/ru_dict", "dict".toByteArray())
                )
            )
        )
        val dest = File(work, "out")

        VoicePackStorage().extractTarBz2(archive.path, dest.path)

        assertTrue(model.contentEquals(File(dest, "ru_RU-denis-medium.onnx").readBytes()))
        assertEquals("a 1", File(dest, "tokens.txt").readText())
        assertEquals("dict", File(dest, "espeak-ng-data/ru_dict").readText())
    }

    @Test
    fun `corrupted archive throws`() {
        val archive = File(work, "broken.tar.bz2").apply { writeBytes(ByteArray(1000) { 7 }) }
        assertFailsWith<Exception> { VoicePackStorage().extractTarBz2(archive.path, File(work, "out").path) }
    }
}
