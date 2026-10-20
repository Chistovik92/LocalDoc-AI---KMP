package com.localdoc.ai.book

/**
 * Читатель составных файлов OLE2 / Compound File Binary (старый бинарный Word `.doc`, Excel, PowerPoint)
 * в общем коде: заголовок → FAT → каталог → потоки (обычные и «мини»-потоки меньше 4 КБ).
 * Любая неконсистентность — [IllegalArgumentException] с понятным текстом, а не бесконечный цикл или падение.
 */
class Ole2File(private val bytes: ByteArray) {

    private class DirEntry(val name: String, val type: Int, val startSector: Int, val size: Long)

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<DirEntry>
    private val miniStream: ByteArray

    init {
        require(isOle2(bytes)) { "Файл не является документом OLE2" }
        require(bytes.size >= HEADER_SIZE) { "Повреждённый документ: заголовок обрезан" }
        val sectorShift = u16(0x1E)
        val miniShift = u16(0x20)
        require(sectorShift in 7..16 && miniShift in 3..sectorShift) { "Повреждённый документ: размер сектора" }
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl miniShift
        miniCutoff = u32(0x38).toInt()

        fat = readFat()
        val dirStart = u32(0x30).toInt()
        entries = parseDirectory(readChain(dirStart, fat))
        val root = entries.firstOrNull { it.type == TYPE_ROOT } ?: throw IllegalArgumentException("Повреждённый документ: нет корневой записи")
        miniStream = if (root.startSector >= 0 && root.size > 0) {
            readChain(root.startSector, fat).let { it.copyOf(minOf(it.size.toLong(), root.size).toInt()) }
        } else ByteArray(0)
        miniFat = readMiniFat()
    }

    /** Имена потоков документа (без каталогов). */
    val streamNames: List<String> get() = entries.filter { it.type == TYPE_STREAM }.map { it.name }

    fun hasStream(name: String): Boolean = entries.any { it.type == TYPE_STREAM && it.name == name }

    /** Содержимое потока верхнего уровня или null, если такого потока нет. */
    fun stream(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.type == TYPE_STREAM && it.name == name } ?: return null
        require(entry.size in 0..bytes.size.toLong() * 2) { "Повреждённый документ: размер потока «$name»" }
        val size = entry.size.toInt()
        val data = if (size < miniCutoff) readMiniChain(entry.startSector, size) else readChain(entry.startSector, fat)
        require(data.size >= size) { "Повреждённый документ: поток «$name» обрезан" }
        return if (data.size == size) data else data.copyOf(size)
    }

    private fun sectorOffset(sector: Int): Int {
        val offset = (sector.toLong() + 1) * sectorSize
        require(sector >= 0 && offset < bytes.size) { "Повреждённый документ: сектор $sector за пределами файла" }
        return offset.toInt()
    }

    private fun readFat(): IntArray {
        val sectorIds = ArrayList<Int>()
        for (i in 0 until 109) {
            val id = s32(0x4C + i * 4)
            if (id >= 0) sectorIds.add(id)
        }
        // Если FAT не помещается в заголовок — продолжение в цепочке DIFAT
        var difat = s32(0x44)
        var guard = 0
        while (difat >= 0 && guard++ < MAX_CHAIN) {
            val off = sectorOffset(difat)
            for (i in 0 until sectorSize / 4 - 1) {
                val id = s32(off + i * 4)
                if (id >= 0) sectorIds.add(id)
            }
            difat = s32(off + sectorSize - 4)
        }
        require(sectorIds.isNotEmpty()) { "Повреждённый документ: нет таблицы размещения" }
        val perSector = sectorSize / 4
        val result = IntArray(sectorIds.size * perSector)
        sectorIds.forEachIndexed { n, sector ->
            val off = sectorOffset(sector)
            for (i in 0 until perSector) result[n * perSector + i] = s32(off + i * 4)
        }
        return result
    }

    private fun readMiniFat(): IntArray {
        val start = s32(0x3C)
        if (start < 0) return IntArray(0)
        val raw = readChain(start, fat)
        return IntArray(raw.size / 4) { i -> le32(raw, i * 4) }
    }

    private fun readChain(start: Int, table: IntArray): ByteArray {
        val out = ByteArrayBuilder()
        var sector = start
        var guard = 0
        while (sector >= 0 && sector < table.size) {
            require(guard++ < MAX_CHAIN) { "Повреждённый документ: цепочка секторов зациклена" }
            val off = sectorOffset(sector)
            out.append(bytes, off, minOf(sectorSize, bytes.size - off))
            require(out.size <= MAX_STREAM_BYTES) { "Документ слишком большой" }
            sector = table[sector]
        }
        return out.toByteArray()
    }

    private fun readMiniChain(start: Int, size: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        val out = ByteArrayBuilder()
        var sector = start
        var guard = 0
        while (sector >= 0 && sector < miniFat.size && out.size < size) {
            require(guard++ < MAX_CHAIN) { "Повреждённый документ: цепочка мини-секторов зациклена" }
            val off = sector.toLong() * miniSectorSize
            require(off + miniSectorSize <= miniStream.size) { "Повреждённый документ: мини-сектор за пределами потока" }
            out.append(miniStream, off.toInt(), miniSectorSize)
            sector = miniFat[sector]
        }
        return out.toByteArray()
    }

    private fun parseDirectory(raw: ByteArray): List<DirEntry> {
        val result = ArrayList<DirEntry>()
        var p = 0
        while (p + DIR_ENTRY_SIZE <= raw.size) {
            val type = raw[p + 0x42].toInt() and 0xFF
            if (type != 0) {
                val nameLength = ((raw[p + 0x40].toInt() and 0xFF) or ((raw[p + 0x41].toInt() and 0xFF) shl 8)).coerceIn(0, 64)
                val chars = CharArray(maxOf(nameLength / 2 - 1, 0)) { i ->
                    ((raw[p + i * 2].toInt() and 0xFF) or ((raw[p + i * 2 + 1].toInt() and 0xFF) shl 8)).toChar()
                }
                result.add(DirEntry(chars.concatToString(), type, le32(raw, p + 0x74), le32(raw, p + 0x78).toLong() and 0xFFFFFFFFL))
            }
            p += DIR_ENTRY_SIZE
        }
        return result
    }

    private fun u16(pos: Int): Int {
        require(pos >= 0 && pos + 2 <= bytes.size) { "Повреждённый документ: файл обрезан" }
        return (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
    }

    private fun s32(pos: Int): Int {
        require(pos >= 0 && pos + 4 <= bytes.size) { "Повреждённый документ: файл обрезан" }
        return le32(bytes, pos)
    }

    private fun u32(pos: Int): Long = s32(pos).toLong() and 0xFFFFFFFFL

    private class ByteArrayBuilder {
        private var data = ByteArray(1024)
        var size = 0
            private set

        fun append(src: ByteArray, off: Int, len: Int) {
            if (size + len > data.size) data = data.copyOf(maxOf(data.size * 2, size + len))
            src.copyInto(data, size, off, off + len)
            size += len
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }

    companion object {
        private const val HEADER_SIZE = 512
        private const val DIR_ENTRY_SIZE = 128
        private const val TYPE_STREAM = 2
        private const val TYPE_ROOT = 5
        private const val MAX_CHAIN = 4_000_000
        private const val MAX_STREAM_BYTES = 256 * 1024 * 1024

        private val SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)

        /** Сигнатура составного файла: D0 CF 11 E0 A1 B1 1A E1. */
        fun isOle2(bytes: ByteArray): Boolean =
            bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { (bytes[it].toInt() and 0xFF) == SIGNATURE[it] }

        private fun le32(b: ByteArray, p: Int): Int =
            (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
    }
}
