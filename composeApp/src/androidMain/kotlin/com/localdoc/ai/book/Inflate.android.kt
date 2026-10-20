package com.localdoc.ai.book

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

actual fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(expectedSize.coerceIn(1024, 64 * 1024 * 1024))
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
