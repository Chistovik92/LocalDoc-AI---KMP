package com.localdoc.ai.book.export

import com.localdoc.ai.book.ByteArrayBuilder

/**
 * Запись ZIP-контейнера (EPUB, DOCX, ODT, FB2.ZIP) без сжатия (метод Stored): формат остаётся
 * валидным для всех читалок, а код не зависит от платформенного Deflate. Имена — UTF-8.
 */
class ZipWriter {

    private class Entry(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)

    private val body = ByteArrayBuilder()
    private val entries = ArrayList<Entry>()
    private val names = HashSet<String>()

    fun add(name: String, data: ByteArray) {
        require(names.add(name)) { "Дубликат файла в архиве: $name" }
        val nameBytes = name.encodeToByteArray()
        val crc = Crc32.of(data)
        val offset = body.size
        body.append(le32(LOCAL_SIG)); body.append(le16(20)); body.append(le16(FLAG_UTF8)); body.append(le16(0))
        body.append(le16(DOS_TIME)); body.append(le16(DOS_DATE)); body.append(le32(crc))
        body.append(le32(data.size.toLong())); body.append(le32(data.size.toLong()))
        body.append(le16(nameBytes.size)); body.append(le16(0))
        body.append(nameBytes); body.append(data)
        entries.add(Entry(nameBytes, crc, data.size, offset))
    }

    fun add(name: String, text: String) = add(name, text.encodeToByteArray())

    fun finish(): ByteArray {
        val centralStart = body.size
        for (e in entries) {
            body.append(le32(CENTRAL_SIG)); body.append(le16(20)); body.append(le16(20)); body.append(le16(FLAG_UTF8)); body.append(le16(0))
            body.append(le16(DOS_TIME)); body.append(le16(DOS_DATE)); body.append(le32(e.crc))
            body.append(le32(e.size.toLong())); body.append(le32(e.size.toLong()))
            body.append(le16(e.name.size)); body.append(le16(0)); body.append(le16(0)); body.append(le16(0)); body.append(le16(0))
            body.append(le32(0)); body.append(le32(e.offset.toLong())); body.append(e.name)
        }
        val centralSize = body.size - centralStart
        body.append(le32(EOCD_SIG)); body.append(le16(0)); body.append(le16(0))
        body.append(le16(entries.size)); body.append(le16(entries.size))
        body.append(le32(centralSize.toLong())); body.append(le32(centralStart.toLong())); body.append(le16(0))
        return body.toByteArray()
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private companion object {
        const val LOCAL_SIG = 0x04034b50L
        const val CENTRAL_SIG = 0x02014b50L
        const val EOCD_SIG = 0x06054b50L
        const val FLAG_UTF8 = 0x0800
        const val DOS_TIME = 0
        const val DOS_DATE = 0x0021 // 1980-01-01
    }
}

/** CRC-32 (IEEE 802.3), как требует ZIP. */
object Crc32 {
    private val table = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Long {
        var c = 0xFFFFFFFFL
        for (b in data) c = table[((c xor b.toLong()) and 0xFF).toInt()] xor (c ushr 8)
        return c xor 0xFFFFFFFFL
    }
}
