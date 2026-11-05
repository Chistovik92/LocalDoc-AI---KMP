package com.localdoc.ai.platform

import kotlin.time.Clock

/**
 * Хранилище файлов журнала отладки: запись, чтение, список по префиксу и удаление по имени.
 * В приложении это [FileAccess], в тестах — память.
 */
interface RingLogFiles {
    fun write(name: String, bytes: ByteArray)
    fun read(name: String): ByteArray?
    fun list(prefix: String): List<String>
    fun delete(name: String)
}

fun FileAccess.asRingLogFiles(): RingLogFiles = object : RingLogFiles {
    override fun write(name: String, bytes: ByteArray) = writeAppFile(name, bytes)
    override fun read(name: String) = readAppFile(name)
    override fun list(prefix: String) = listAppFiles(prefix)
    override fun delete(name: String) = deleteAppFile(name)
}

/**
 * Кольцевой журнал отладки приложения: переводы, сеть, ошибки. Строки накапливаются в ограниченном
 * буфере в памяти и периодически сбрасываются на диск ротацией файлов `debuglog.<n>.log`. Старые файлы
 * затираются новыми (кольцо), поэтому объём на диске ограничен, а последние события переживают
 * перезапуск. В 2.7.0 (вход через Google/iCloud) файлы журнала синхронизируются в облако вместе с
 * остальными данными — для этого они лежат в общем хранилище приложения и читаются через [readAll].
 *
 * Журнал дешёвый и не роняет приложение: сбой записи на диск проглатывается.
 */
object RingLog {

    /** Число строк, после которого буфер сбрасывается на диск. */
    private const val FLUSH_AT_LINES = 32
    /** Максимум строк в буфере памяти (лишние самые старые отбрасываются). */
    private const val MAX_BUFFER_LINES = 512
    /** Предел одного файла в символах (кириллица — 2 байта, так что примерно вдвое больше байт). */
    private const val MAX_FILE_CHARS = 128 * 1024
    /** Сколько файлов хранится; старейшие затираются. */
    private const val MAX_FILES = 4

    private const val FILE_PREFIX = "debuglog."
    private const val FILE_SUFFIX = ".log"

    private val lock = SyncLock()
    private var files: RingLogFiles? = null
    private var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    private val buffer = ArrayDeque<String>()

    /** Подключает хранилище файлов и часы (для тестов). Накопленное до этого сбрасывается сразу. */
    fun bind(files: RingLogFiles, clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) = lock.withLock {
        this.files = files
        this.clock = clock
        flushLocked()
    }

    fun d(message: String) = record("D", message, null)

    fun e(message: String, error: Throwable? = null) = record("E", message, error)

    private fun record(level: String, message: String, error: Throwable?) {
        val errorPart = error?.let { " | ${it::class.simpleName ?: "Throwable"}: ${it.message}" }.orEmpty()
        lock.withLock {
            val line = "${clock()} $level $message$errorPart"
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER_LINES) buffer.removeFirst()
            if (buffer.size >= FLUSH_AT_LINES) flushLocked()
        }
    }

    /** Принудительно записывает накопленные строки на диск. */
    fun flush() = lock.withLock { flushLocked() }

    /** Сбрасывает состояние для тестов (синглтон живёт всю жизнь процесса). */
    internal fun resetForTests() = lock.withLock {
        files = null
        buffer.clear()
    }

    /** Все сохранённые строки в порядке записи — для показа и будущей синхронизации в облако. */
    fun readAll(): String = lock.withLock {
        val builder = StringBuilder()
        files?.let { f ->
            sortedFiles(f).forEach { name -> f.read(name)?.let { builder.append(it.decodeToString()) } }
        }
        if (buffer.isNotEmpty()) {
            buffer.forEach { builder.append(it).append('\n') }
        }
        builder.toString()
    }

    private fun flushLocked() {
        val f = files ?: return
        if (buffer.isEmpty()) return
        val chunk = buffer.joinToString("\n", postfix = "\n")
        buffer.clear()
        try {
            appendToDisk(f, chunk)
        } catch (_: Throwable) {
            // Журнал отладки не должен ронять приложение
        }
    }

    private fun appendToDisk(f: RingLogFiles, chunk: String) {
        var offset = 0
        while (offset < chunk.length) {
            val name = activeFile(f) ?: "${FILE_PREFIX}0$FILE_SUFFIX"
            val existing = f.read(name)?.decodeToString() ?: ""
            if (existing.length >= MAX_FILE_CHARS) {
                // Файл заполнен — ротация на следующий номер (старейшие файлы затем отбрасываются)
                f.write("${FILE_PREFIX}${seqOf(name) + 1}$FILE_SUFFIX", ByteArray(0))
                continue
            }
            val space = MAX_FILE_CHARS - existing.length
            val take = minOf(space, chunk.length - offset)
            f.write(name, (existing + chunk.substring(offset, offset + take)).encodeToByteArray())
            offset += take
            pruneFiles(f)
        }
    }

    /** Файл с наибольшим номером — в него дописываются новые строки. */
    private fun activeFile(f: RingLogFiles): String? = sortedFiles(f).lastOrNull()

    private fun sortedFiles(f: RingLogFiles): List<String> =
        f.list(FILE_PREFIX).filter { it.startsWith(FILE_PREFIX) && it.endsWith(FILE_SUFFIX) }.sortedBy(::seqOf)

    private fun seqOf(name: String): Int =
        name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toIntOrNull() ?: 0

    private fun pruneFiles(f: RingLogFiles) {
        val names = sortedFiles(f)
        if (names.size > MAX_FILES) names.dropLast(MAX_FILES).forEach(f::delete)
    }
}
