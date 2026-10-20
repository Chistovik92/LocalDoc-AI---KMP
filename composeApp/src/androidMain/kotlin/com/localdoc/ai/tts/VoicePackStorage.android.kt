package com.localdoc.ai.tts

import com.localdoc.ai.platform.AppContextHolder
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/** Android: голоса в files/voices (рядом с моделями), bzip2 — commons-compress. */
actual class VoicePackStorage actual constructor() {

    private val root: File by lazy {
        val ctx = AppContextHolder.appContext
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        File(base, "voices").apply { mkdirs() }
    }

    actual fun rootDir(): String = root.absolutePath

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun deleteRecursively(path: String) {
        File(path).deleteRecursively()
    }

    actual fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

    actual fun extractTarBz2(archivePath: String, destDir: String) {
        val dest = File(destDir).apply { mkdirs() }
        val sink = FileTarSink(dest)
        try {
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archivePath), BUFFER)).use { input ->
                TarReader { buffer, offset, length -> input.read(buffer, offset, length) }
                    .extractTo(sink, stripComponents = 1)
            }
        } finally {
            // Ошибка посреди файла не должна оставлять открытый дескриптор
            sink.endFile()
        }
    }

    private class FileTarSink(private val root: File) : TarSink {
        private var current: OutputStream? = null

        override fun directory(path: String) {
            File(root, path).mkdirs()
        }

        override fun beginFile(path: String) {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            current = FileOutputStream(file).buffered(BUFFER)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            current?.write(bytes, offset, length)
        }

        override fun endFile() {
            current?.close()
            current = null
        }
    }

    private companion object {
        const val BUFFER = 1 shl 16
    }
}
