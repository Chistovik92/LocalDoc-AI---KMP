package com.localdoc.ai.book

/**
 * Сжатие raw DEFLATE (RFC 1951, без zlib-заголовка) — обратное к [inflateRaw]:
 * Android — java.util.zip.Deflater, iOS — NSData (libcompression).
 */
expect fun deflateRaw(data: ByteArray): ByteArray
