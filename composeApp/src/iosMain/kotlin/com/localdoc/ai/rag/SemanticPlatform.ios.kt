@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.rag

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

actual fun totalDeviceMemoryMb(): Long = (NSProcessInfo.processInfo.physicalMemory / (1024u * 1024u)).toLong()

/** ONNX Runtime в iOS-сборку пока не подключён: там работает хеширование признаков. */
actual val onnxRuntimeAvailable: Boolean = false

actual fun createOnnxTextEncoder(modelPath: String): OnnxTextEncoder =
    throw UnsupportedOperationException("ONNX Runtime недоступен на iOS")

actual fun readFileChunks(path: String, chunkSize: Int, onChunk: (buffer: ByteArray, length: Int) -> Unit): Boolean {
    val file = fopen(path, "rb") ?: return false
    try {
        val buffer = ByteArray(chunkSize)
        while (true) {
            val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), chunkSize.convert(), file) }.toInt()
            if (read <= 0) break
            onChunk(buffer, read)
        }
    } finally {
        fclose(file)
    }
    return true
}
