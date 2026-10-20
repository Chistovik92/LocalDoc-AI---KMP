package com.localdoc.ai.tts

/** Приёмник распакованных файлов. Пути относительные и уже проверены [TarReader]. */
interface TarSink {
    fun directory(path: String)
    fun beginFile(path: String)
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun endFile()
}

/** Повреждённый или неподдерживаемый архив. */
class ArchiveException(message: String) : Exception(message)

/**
 * Потоковый разбор tar (ustar/GNU/pax) — так упакованы голоса sherpa-onnx.
 * Источник [read] отдаёт уже распакованные байты (bzip2 снимает платформа) и
 * возвращает -1 в конце потока. Ссылки и спецфайлы пропускаются; пути вида «../»
 * и абсолютные отклоняются, чтобы архив не мог писать за пределы каталога голоса.
 */
class TarReader(private val read: (ByteArray, Int, Int) -> Int) {

    private val header = ByteArray(BLOCK)
    private val buffer = ByteArray(1 shl 16)

    /** Распаковывает весь архив, отбрасывая первые [stripComponents] компонентов пути. */
    fun extractTo(sink: TarSink, stripComponents: Int = 0) {
        var longName: String? = null
        var paxPath: String? = null
        while (true) {
            if (!readFully(header, BLOCK)) throw ArchiveException("Архив обрывается на заголовке")
            if (header.all { it == 0.toByte() }) return
            verifyChecksum()
            val size = parseSize()
            val type = header[TYPE_OFFSET].toInt().toChar()
            when (type) {
                GNU_LONG_NAME -> {
                    longName = readText(size)
                    continue
                }
                PAX_HEADER -> {
                    paxPath = parsePaxPath(readText(size)) ?: paxPath
                    continue
                }
                PAX_GLOBAL -> {
                    skip(size)
                    continue
                }
            }
            val rawName = paxPath ?: longName ?: headerName()
            longName = null
            paxPath = null
            val path = sanitize(rawName, stripComponents)
            when (type) {
                REGULAR, REGULAR_OLD -> {
                    if (path == null) skip(size) else copyFile(path, size, sink)
                }
                DIRECTORY -> {
                    path?.let(sink::directory)
                    skip(size)
                }
                // Ссылки, устройства, FIFO в голосовых архивах не нужны
                else -> skip(size)
            }
        }
    }

    private fun copyFile(path: String, size: Long, sink: TarSink) {
        sink.beginFile(path)
        var left = size
        while (left > 0) {
            val chunk = minOf(left, buffer.size.toLong()).toInt()
            val n = read(buffer, 0, chunk)
            if (n <= 0) throw ArchiveException("Архив обрывается внутри файла $path")
            sink.write(buffer, 0, n)
            left -= n
        }
        sink.endFile()
        skipPadding(size)
    }

    private fun readText(size: Long): String {
        if (size > MAX_META_SIZE) throw ArchiveException("Слишком длинный служебный заголовок")
        val bytes = ByteArray(size.toInt())
        if (!readFully(bytes, bytes.size)) throw ArchiveException("Архив обрывается на заголовке")
        skipPadding(size)
        return bytes.decodeToString().trimEnd('\u0000', '\n')
    }

    private fun skip(size: Long) {
        var left = size + padding(size)
        while (left > 0) {
            val n = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n <= 0) throw ArchiveException("Архив обрывается")
            left -= n
        }
    }

    private fun skipPadding(size: Long) {
        val pad = padding(size)
        if (pad > 0 && !readFully(buffer, pad.toInt())) throw ArchiveException("Архив обрывается")
    }

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun readFully(target: ByteArray, length: Int): Boolean {
        var done = 0
        while (done < length) {
            val n = read(target, done, length - done)
            if (n <= 0) return false
            done += n
        }
        return true
    }

    private fun headerName(): String {
        val name = cString(0, NAME_LENGTH)
        val isUstar = cString(MAGIC_OFFSET, 5) == "ustar"
        val prefix = if (isUstar) cString(PREFIX_OFFSET, PREFIX_LENGTH) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun cString(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return header.decodeToString(offset, end)
    }

    /** Размер: восьмеричное ASCII-число или base-256 (старший бит) для файлов > 8 ГБ. */
    private fun parseSize(): Long {
        if (header[SIZE_OFFSET].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in SIZE_OFFSET + 1 until SIZE_OFFSET + SIZE_LENGTH) value = (value shl 8) or (header[i].toLong() and 0xFF)
            return value
        }
        val text = cString(SIZE_OFFSET, SIZE_LENGTH).trim()
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8)?.takeIf { it >= 0 } ?: throw ArchiveException("Некорректный размер файла в архиве")
    }

    private fun verifyChecksum() {
        val stored = cString(CHECKSUM_OFFSET, CHECKSUM_LENGTH).trim().toLongOrNull(8)
            ?: throw ArchiveException("Файл не является tar-архивом")
        var sum = 0L
        for (i in 0 until BLOCK) {
            sum += if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) ' '.code else header[i].toInt() and 0xFF
        }
        if (sum != stored) throw ArchiveException("Повреждён заголовок tar-архива")
    }

    companion object {
        private const val BLOCK = 512
        private const val NAME_LENGTH = 100
        private const val SIZE_OFFSET = 124
        private const val SIZE_LENGTH = 12
        private const val CHECKSUM_OFFSET = 148
        private const val CHECKSUM_LENGTH = 8
        private const val TYPE_OFFSET = 156
        private const val MAGIC_OFFSET = 257
        private const val PREFIX_OFFSET = 345
        private const val PREFIX_LENGTH = 155
        private const val MAX_META_SIZE = 1L shl 20

        private const val REGULAR = '0'
        private const val REGULAR_OLD = '\u0000'
        private const val DIRECTORY = '5'
        private const val GNU_LONG_NAME = 'L'
        private const val PAX_HEADER = 'x'
        private const val PAX_GLOBAL = 'g'

        /** Записи pax: «<длина> path=<путь>\n». */
        internal fun parsePaxPath(text: String): String? =
            text.lineSequence()
                .map { it.substringAfter(' ', "") }
                .firstOrNull { it.startsWith("path=") }
                ?.removePrefix("path=")

        /**
         * Нормализует путь записи: убирает «./», отбрасывает первые компоненты.
         * null — запись надо пропустить (пустой путь после отбрасывания).
         * Абсолютные пути и «..» — признак вредного архива: ошибка.
         */
        internal fun sanitize(raw: String, stripComponents: Int): String? {
            val unified = raw.replace('\\', '/')
            if (unified.startsWith("/") || (unified.length > 1 && unified[1] == ':')) {
                throw ArchiveException("Абсолютный путь в архиве: $raw")
            }
            val parts = unified.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw ArchiveException("Недопустимый путь в архиве: $raw")
            val kept = parts.drop(stripComponents)
            return if (kept.isEmpty()) null else kept.joinToString("/")
        }
    }
}
