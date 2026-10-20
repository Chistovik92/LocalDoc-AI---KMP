package com.localdoc.ai.book

/** Сборка тестовых файлов «с нуля»: ZIP (без сжатия) и составной файл OLE2 с документом Word 97. */
internal object TestFiles {

    // ---------- ZIP ----------

    fun zip(entries: Map<String, String>): ByteArray = zipBytes(entries.mapValues { it.value.encodeToByteArray() })

    fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        val central = ArrayList<Byte>()
        for ((name, content) in entries) {
            val nameBytes = name.encodeToByteArray()
            val data = content
            val offset = out.size
            out.addAll(le32(0x04034b50)); out.addAll(le16(20)); out.addAll(le16(0)); out.addAll(le16(0))
            out.addAll(le16(0)); out.addAll(le16(0)); out.addAll(le32(0)); out.addAll(le32(data.size)); out.addAll(le32(data.size))
            out.addAll(le16(nameBytes.size)); out.addAll(le16(0)); out.addAll(nameBytes.toList()); out.addAll(data.toList())

            central.addAll(le32(0x02014b50)); central.addAll(le16(20)); central.addAll(le16(20)); central.addAll(le16(0))
            central.addAll(le16(0)); central.addAll(le16(0)); central.addAll(le16(0)); central.addAll(le32(0))
            central.addAll(le32(data.size)); central.addAll(le32(data.size)); central.addAll(le16(nameBytes.size))
            central.addAll(le16(0)); central.addAll(le16(0)); central.addAll(le16(0)); central.addAll(le16(0))
            central.addAll(le32(0)); central.addAll(le32(offset)); central.addAll(nameBytes.toList())
        }
        val centralOffset = out.size
        out.addAll(central)
        out.addAll(le32(0x06054b50)); out.addAll(le16(0)); out.addAll(le16(0))
        out.addAll(le16(entries.size)); out.addAll(le16(entries.size))
        out.addAll(le32(central.size)); out.addAll(le32(centralOffset)); out.addAll(le16(0))
        return out.toByteArray()
    }

    /** Минимальный .docx: тело документа и (необязательно) styles.xml и метаданные. */
    fun docx(body: String, styles: String? = null, title: String? = null): ByteArray {
        val files = linkedMapOf(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
        )
        if (styles != null) files["word/styles.xml"] = """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">$styles</w:styles>"""
        if (title != null) files["docProps/core.xml"] = """<cp:coreProperties xmlns:cp="a" xmlns:dc="b"><dc:title>$title</dc:title><dc:creator>Автор Тестов</dc:creator></cp:coreProperties>"""
        return zip(files)
    }

    // ---------- OLE2 + Word 97 ----------

    private const val SECTOR = 512
    private const val MINI_SECTOR = 64
    private const val FREE = -1
    private const val END = -2
    private const val FATSECT = -3

    /** Составной файл с произвольными потоками; потоки короче 4096 байт кладутся в «мини»-поток. */
    fun ole2(streams: Map<String, ByteArray>): ByteArray {
        // --- мини-поток и мини-FAT ---
        val mini = ArrayList<Byte>()
        val miniFat = ArrayList<Int>()
        val big = ArrayList<Pair<String, ByteArray>>()
        val miniStart = HashMap<String, Int>()
        for ((name, data) in streams) {
            if (data.size < 4096) {
                val first = miniFat.size
                miniStart[name] = if (data.isEmpty()) END else first
                val count = (data.size + MINI_SECTOR - 1) / MINI_SECTOR
                for (i in 0 until count) miniFat.add(if (i == count - 1) END else first + i + 1)
                mini.addAll(data.copyOf(count * MINI_SECTOR).toList())
            } else big.add(name to data)
        }

        // --- раскладка секторов: [FAT][каталог][миниFAT][мини-поток][потоки…] ---
        fun sectors(bytes: Int) = (bytes + SECTOR - 1) / SECTOR
        val dirSectors = sectors((streams.size + 1) * 128)
        val miniFatBytes = ByteArray(sectors(miniFat.size * 4) * SECTOR) { 0xFF.toByte() }
        miniFat.forEachIndexed { i, v -> put32(miniFatBytes, i * 4, v) }
        val miniFatSectors = if (miniFat.isEmpty()) 0 else miniFatBytes.size / SECTOR
        val miniStreamSectors = sectors(mini.size)
        val bigSectors = big.map { sectors(it.second.size) }
        val dataSectors = dirSectors + miniFatSectors + miniStreamSectors + bigSectors.sum()
        var fatSectors = 1
        while (fatSectors * (SECTOR / 4) < dataSectors + fatSectors) fatSectors++
        val total = fatSectors + dataSectors
        val fat = IntArray(fatSectors * SECTOR / 4) { FREE }
        var next = 0
        repeat(fatSectors) { fat[next++] = FATSECT }
        fun chain(count: Int): Int {
            if (count == 0) return END
            val start = next
            for (i in 0 until count) { fat[next] = if (i == count - 1) END else next + 1; next++ }
            return start
        }
        val dirStart = chain(dirSectors)
        val miniFatStart = if (miniFatSectors == 0) END else chain(miniFatSectors)
        val miniStreamStart = if (miniStreamSectors == 0) END else chain(miniStreamSectors)
        val bigStart = big.indices.map { chain(bigSectors[it]) }

        // --- каталог ---
        val dir = ByteArray(dirSectors * SECTOR)
        fun entry(index: Int, name: String, type: Int, start: Int, size: Int) {
            val p = index * 128
            name.forEachIndexed { i, c -> dir[p + i * 2] = (c.code and 0xFF).toByte(); dir[p + i * 2 + 1] = (c.code shr 8).toByte() }
            dir[p + 0x40] = ((name.length + 1) * 2).toByte()
            dir[p + 0x42] = type.toByte()
            put32(dir, p + 0x74, start)
            put32(dir, p + 0x78, size)
        }
        entry(0, "Root Entry", 5, miniStreamStart, mini.size)
        var idx = 1
        for ((name, data) in streams) {
            val start = miniStart[name] ?: bigStart[big.indexOfFirst { it.first == name }]
            entry(idx++, name, 2, start, data.size)
        }

        // --- сборка файла ---
        val file = ByteArray((total + 1) * SECTOR)
        intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1).forEachIndexed { i, b -> file[i] = b.toByte() }
        put16(file, 0x18, 0x3E); put16(file, 0x1A, 3); put16(file, 0x1C, 0xFFFE)
        put16(file, 0x1E, 9); put16(file, 0x20, 6)
        put32(file, 0x2C, fatSectors); put32(file, 0x30, dirStart); put32(file, 0x38, 4096)
        put32(file, 0x3C, miniFatStart); put32(file, 0x40, miniFatSectors); put32(file, 0x44, END); put32(file, 0x48, 0)
        for (i in 0 until 109) put32(file, 0x4C + i * 4, if (i < fatSectors) i else FREE)
        fun write(sector: Int, bytes: ByteArray) = bytes.copyInto(file, (sector + 1) * SECTOR)
        val fatBytes = ByteArray(fat.size * 4)
        fat.forEachIndexed { i, v -> put32(fatBytes, i * 4, v) }
        write(0, fatBytes)
        write(dirStart, dir)
        if (miniFatSectors > 0) write(miniFatStart, miniFatBytes)
        if (miniStreamSectors > 0) write(miniStreamStart, mini.toByteArray())
        big.forEachIndexed { i, (_, data) -> write(bigStart[i], data) }
        return file
    }

    /**
     * Документ Word 97: основной текст [paragraphs] (каждый абзац заканчивается 0x0D), кодированный
     * либо как UTF-16 (по умолчанию), либо как «сжатый» cp1252-фрагмент. [miniTable] кладёт таблицу
     * кусков текста в мини-поток, иначе обе части лежат в обычных секторах.
     */
    fun doc(paragraphs: List<String>, compressed: Boolean = false, encrypted: Boolean = false, miniTable: Boolean = false): ByteArray {
        val text = paragraphs.joinToString("") { it + "\r" }
        val textBytes = if (compressed) {
            ByteArray(text.length) { text[it].code.toByte() }
        } else {
            ByteArray(text.length * 2).also { b -> text.forEachIndexed { i, c -> b[i * 2] = (c.code and 0xFF).toByte(); b[i * 2 + 1] = (c.code shr 8).toByte() } }
        }
        val textOffset = 0x800
        val word = ByteArray(maxOf(4096, textOffset + textBytes.size + 16))
        put16(word, 0, 0xA5EC)
        put16(word, 2, 0x00C1)
        put16(word, 0x0A, 0x0200 or if (encrypted) 0x0100 else 0)
        put16(word, 0x20, 14)                     // csw
        put16(word, 0x3E, 22)                     // cslw
        put32(word, 0x40 + 12, text.length)       // ccpText
        val lcbStart = 0x40 + 22 * 4 + 2
        put16(word, lcbStart - 2, 93)             // cbRgFcLcb

        // Clx: 0x02, lcb=16, PlcPcd = CP0, CP1, один PCD (flags, fc, prm)
        val clx = ByteArray(21)
        clx[0] = 2
        put32(clx, 1, 16)
        put32(clx, 5, 0)
        put32(clx, 9, text.length)
        put32(clx, 15, if (compressed) (textOffset * 2) or 0x40000000 else textOffset)
        put32(word, lcbStart + 33 * 8, 0)         // fcClx
        put32(word, lcbStart + 33 * 8 + 4, clx.size)
        textBytes.copyInto(word, textOffset)
        return ole2(mapOf("WordDocument" to word, "1Table" to if (miniTable) clx else clx.copyOf(4096)))
    }

    private fun le16(v: Int) = listOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = listOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun put16(b: ByteArray, p: Int, v: Int) { b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte() }
    private fun put32(b: ByteArray, p: Int, v: Int) {
        b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte(); b[p + 2] = (v shr 16).toByte(); b[p + 3] = (v shr 24).toByte()
    }
}
