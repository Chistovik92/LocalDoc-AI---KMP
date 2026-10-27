package com.localdoc.ai.book

/** Сборка тестового Mobi «с нуля»: PDB → запись 0 (PalmDOC + MOBI + EXTH) → текстовые записи → картинки. */
internal object TestMobi {

    val png = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13,
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20
    )

    /** Простейший сжиматель PalmDOC: «пробел + символ», ссылки назад и литералы — те же приёмы, что в распаковщике. */
    fun palmDocCompress(input: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            var bestLen = 0
            var bestDist = 0
            for (dist in 1..minOf(i, 2047)) {
                var len = 0
                while (len < 10 && i + len < input.size && input[i + len] == input[i + len - dist]) len++
                if (len > bestLen) { bestLen = len; bestDist = dist }
            }
            when {
                bestLen >= 3 -> {
                    val pair = 0x8000 or (bestDist shl 3) or (bestLen - 3)
                    out.add((pair shr 8).toByte()); out.add(pair.toByte())
                    i += bestLen
                }
                c == 0x20 && i + 1 < input.size && (input[i + 1].toInt() and 0xFF) in 0x40..0x7F -> {
                    out.add((input[i + 1].toInt() xor 0x80).toByte())
                    i += 2
                }
                c == 0 || c in 9..0x7F -> { out.add(c.toByte()); i++ }
                else -> { out.add(1); out.add(c.toByte()); i++ }
            }
        }
        return out.toByteArray()
    }

    class Spec(
        val text: ByteArray,
        val title: String = "Тестовая книга",
        val author: String = "Автор Тестов",
        val compression: Int = 2,
        val images: List<ByteArray> = emptyList(),
        val coverIndex: Int = -1,
        val recordSize: Int = 4096,
        val trailing: Boolean = false,
        val encryption: Int = 0
    )

    fun build(spec: Spec): ByteArray {
        val textRecords = spec.text.toList().chunked(spec.recordSize).map { it.toByteArray() }.map { raw ->
            val packed = if (spec.compression == 2) palmDocCompress(raw) else raw
            // Хвост записи: два служебных байта и размер 3 (бит 1 флагов extra data)
            if (spec.trailing) packed + byteArrayOf(7, 7, (0x80 or 3).toByte()) else packed
        }
        val firstImage = if (spec.images.isEmpty()) 0 else 1 + textRecords.size

        val nameBytes = spec.title.encodeToByteArray()
        val exth = ArrayList<Byte>()
        var exthCount = 0
        fun exthRecord(type: Int, data: ByteArray) {
            exth.addAll(be32(type)); exth.addAll(be32(8 + data.size)); exth.addAll(data.toList()); exthCount++
        }
        exthRecord(100, spec.author.encodeToByteArray())
        if (spec.coverIndex >= 0) exthRecord(201, be32(spec.coverIndex).toByteArray())
        var exthBlock = "EXTH".encodeToByteArray().toList() + be32(12 + exth.size) + be32(exthCount) + exth
        while (exthBlock.size % 4 != 0) exthBlock = exthBlock + 0.toByte()

        val mobiLength = 232
        val header = ByteArray(16 + mobiLength)
        fun put32(offset: Int, v: Int) = be32(v).forEachIndexed { k, b -> header[offset + k] = b }
        fun put16(offset: Int, v: Int) { header[offset] = (v shr 8).toByte(); header[offset + 1] = v.toByte() }
        put16(0, spec.compression)
        put32(4, spec.text.size)
        put16(8, textRecords.size)
        put16(10, spec.recordSize)
        put16(12, spec.encryption)
        "MOBI".encodeToByteArray().forEachIndexed { k, b -> header[16 + k] = b }
        put32(20, mobiLength)
        put32(28, 65001)
        put32(36, 6)
        put32(108, firstImage)
        put32(128, 0x40)
        if (spec.trailing) put16(0xF2, 2)
        val nameOffset = header.size + exthBlock.size
        put32(84, nameOffset)
        put32(88, nameBytes.size)
        val record0 = header + exthBlock.toByteArray() + nameBytes + ByteArray(4)

        val records = listOf(record0) + textRecords + spec.images
        val listEnd = 78 + records.size * 8 + 2
        val pdb = ByteArray(listEnd)
        "Test".encodeToByteArray().forEachIndexed { k, b -> pdb[k] = b }
        "BOOKMOBI".encodeToByteArray().forEachIndexed { k, b -> pdb[60 + k] = b }
        pdb[76] = (records.size shr 8).toByte(); pdb[77] = records.size.toByte()
        var offset = listEnd
        records.forEachIndexed { i, r ->
            be32(offset).forEachIndexed { k, b -> pdb[78 + i * 8 + k] = b }
            offset += r.size
        }
        return records.fold(pdb) { acc, r -> acc + r }
    }

    private fun be32(v: Int): List<Byte> = listOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** HTML книги с оглавлением по `filepos`: смещения глав считаются по байтам готового текста. */
    fun htmlWithToc(chapters: List<Pair<String, String>>): ByteArray {
        fun toc(offsets: List<Int>) = buildString {
            append("<html><head></head><body><p>Содержание</p>")
            chapters.forEachIndexed { i, (title, _) -> append("<p><a filepos=${offsets[i].toString().padStart(10, '0')}>$title</a></p>") }
            append("<mbp:pagebreak/>")
        }
        val tocSize = toc(chapters.map { 0 }).encodeToByteArray().size
        val offsets = ArrayList<Int>()
        var pos = tocSize
        val bodies = chapters.map { (title, body) ->
            offsets.add(pos)
            "<h2>$title</h2><p>$body</p><mbp:pagebreak/>".encodeToByteArray().also { pos += it.size }
        }
        val tail = "</body></html>".encodeToByteArray()
        return bodies.fold(toc(offsets).encodeToByteArray()) { acc, b -> acc + b } + tail
    }
}
