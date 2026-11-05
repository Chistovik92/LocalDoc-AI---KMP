package com.localdoc.ai.book

import com.localdoc.ai.platform.toByteArray
import com.localdoc.ai.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.compressedDataUsingAlgorithm

/** iOS: NSDataCompressionAlgorithmZlib — raw DEFLATE (RFC 1951), как в ZIP (см. [inflateRaw]). */
@OptIn(ExperimentalForeignApi::class)
actual fun deflateRaw(data: ByteArray): ByteArray {
    if (data.isEmpty()) return ByteArray(0)
    val result = data.toNSData().compressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, error = null)
        ?: throw IllegalStateException("Не удалось сжать данные")
    return result.toByteArray()
}
