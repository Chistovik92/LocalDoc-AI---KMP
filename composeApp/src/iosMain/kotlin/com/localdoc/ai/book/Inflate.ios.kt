package com.localdoc.ai.book

import com.localdoc.ai.platform.toByteArray
import com.localdoc.ai.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDataCompressionAlgorithm
import platform.Foundation.decompressedDataUsingAlgorithm

/** iOS: NSDataCompressionAlgorithmZlib — это raw DEFLATE (RFC 1951), как в ZIP. */
@OptIn(ExperimentalForeignApi::class)
actual fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray {
    if (data.isEmpty()) return ByteArray(0)
    val result = data.toNSData().decompressedDataUsingAlgorithm(
        NSDataCompressionAlgorithm.NSDataCompressionAlgorithmZlib,
        error = null
    ) ?: throw IllegalArgumentException("Не удалось распаковать данные ZIP")
    return result.toByteArray()
}
