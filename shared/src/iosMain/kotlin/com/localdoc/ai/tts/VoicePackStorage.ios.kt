@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.tts

import com.localdoc.ai.bzip2.BZ2_bzDecompress
import com.localdoc.ai.bzip2.BZ2_bzDecompressEnd
import com.localdoc.ai.bzip2.BZ2_bzDecompressInit
import com.localdoc.ai.bzip2.BZ_OK
import com.localdoc.ai.bzip2.BZ_STREAM_END
import com.localdoc.ai.bzip2.bz_stream
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.memset

/** iOS: голоса в Documents/voices (рядом с моделями), bzip2 — системная libbz2. */
actual class VoicePackStorage actual constructor() {

    private val root: String by lazy { "${NSHomeDirectory()}/Documents/voices".also(::makeDirs) }

    actual fun rootDir(): String = root

    actual fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun deleteRecursively(path: String) {
        if (exists(path)) NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    actual fun rename(from: String, to: String): Boolean =
        NSFileManager.defaultManager.moveItemAtPath(from, toPath = to, error = null)

    actual fun extractTarBz2(archivePath: String, destDir: String) {
        makeDirs(destDir)
        val file = fopen(archivePath, "rb") ?: throw ArchiveException("Не удалось открыть архив голоса")
        val source = Bz2Source(file)
        val sink = PosixTarSink(destDir)
        try {
            TarReader(source::read).extractTo(sink, stripComponents = 1)
        } finally {
            sink.endFile()
            source.close()
            fclose(file)
        }
    }
}

private fun makeDirs(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
}

/** Запись файлов архива через POSIX. */
private class PosixTarSink(private val root: String) : TarSink {
    private var current: CPointer<FILE>? = null

    override fun directory(path: String) = makeDirs("$root/$path")

    override fun beginFile(path: String) {
        val full = "$root/$path"
        makeDirs(full.substringBeforeLast('/'))
        current = fopen(full, "wb") ?: throw ArchiveException("Не удалось создать файл $path")
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val file = current ?: return
        val written = bytes.usePinned { fwrite(it.addressOf(offset), 1u, length.convert(), file) }
        if (written != length.toULong()) throw ArchiveException("Недостаточно места на устройстве")
    }

    override fun endFile() {
        current?.let { fclose(it) }
        current = null
    }
}

/**
 * Потоковая распаковка bzip2 (libbz2, низкоуровневый API). Поддерживает несколько
 * склеенных потоков подряд — так пишут параллельные упаковщики (pbzip2, lbzip2).
 */
private class Bz2Source(private val file: CPointer<FILE>) {
    private val stream = nativeHeap.alloc<bz_stream>()
    private val input = nativeHeap.allocArray<ByteVar>(INPUT_SIZE)
    private var inputEof = false
    private var finished = false
    private var open = false

    init {
        // nativeHeap — это malloc: память не обнулена, входной буфер пока пуст
        memset(stream.ptr, 0, sizeOf<bz_stream>().convert())
        startStream()
    }

    private fun startStream() {
        // Для склеенного потока сохраняем позицию во входном буфере (в первый раз — null/0)
        val nextIn = stream.next_in
        val availIn = stream.avail_in
        memset(stream.ptr, 0, sizeOf<bz_stream>().convert())
        stream.next_in = nextIn
        stream.avail_in = availIn
        val rc = BZ2_bzDecompressInit(stream.ptr, 0, 0)
        if (rc != BZ_OK) throw ArchiveException("Не удалось начать распаковку (bzip2 $rc)")
        open = true
    }

    private fun refill() {
        if (stream.avail_in != 0u || inputEof) return
        val n = fread(input, 1u, INPUT_SIZE.convert(), file)
        if (n == 0uL) inputEof = true
        stream.next_in = input
        stream.avail_in = n.convert()
    }

    fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (length == 0) return 0
        return target.usePinned { pinned ->
            var produced = 0
            while (produced == 0 && !finished) {
                refill()
                stream.next_out = pinned.addressOf(offset)
                stream.avail_out = length.convert()
                val rc = BZ2_bzDecompress(stream.ptr)
                produced = length - stream.avail_out.toInt()
                when (rc) {
                    BZ_OK -> if (produced == 0 && inputEof && stream.avail_in == 0u) {
                        throw ArchiveException("Архив голоса обрывается — скачайте его заново")
                    }
                    BZ_STREAM_END -> {
                        BZ2_bzDecompressEnd(stream.ptr)
                        open = false
                        refill()
                        // Следующий склеенный поток или конец файла
                        if (stream.avail_in == 0u) finished = true else startStream()
                    }
                    else -> throw ArchiveException("Архив голоса повреждён (bzip2 $rc)")
                }
            }
            if (produced == 0) -1 else produced
        }
    }

    fun close() {
        if (open) BZ2_bzDecompressEnd(stream.ptr)
        open = false
        nativeHeap.free(input)
        nativeHeap.free(stream)
    }

    companion object {
        private const val INPUT_SIZE = 1 shl 16
    }
}
