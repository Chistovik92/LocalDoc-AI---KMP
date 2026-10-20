package com.localdoc.ai.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Запись тестового tar-архива. */
internal class TarEntry(val name: String, val data: ByteArray = ByteArray(0), val type: Char = '0')

/** Собирает ustar-архив вручную — так же, как его пишет GNU tar. */
internal fun buildTar(entries: List<TarEntry>): ByteArray {
    val out = ArrayList<Byte>()
    fun header(name: String, size: Int, type: Char): ByteArray {
        val h = ByteArray(512)
        fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(h, offset)
        put(0, name)
        put(100, "0000644\u0000")
        put(108, "0001750\u0000")
        put(116, "0001750\u0000")
        put(124, size.toString(8).padStart(11, '0') + "\u0000")
        put(136, "14707740000\u0000")
        h[156] = type.code.toByte()
        put(257, "ustar\u0000")
        put(263, "00")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }
    fun pad(n: Int) = repeat((512 - n % 512) % 512) { out.add(0) }
    for (e in entries) {
        if (e.name.length > 99) {
            val longName = (e.name + "\u0000").encodeToByteArray()
            out.addAll(header("././@LongLink", longName.size, 'L').toList())
            out.addAll(longName.toList()); pad(longName.size)
            out.addAll(header(e.name.take(99), e.data.size, e.type).toList())
        } else {
            out.addAll(header(e.name, e.data.size, e.type).toList())
        }
        out.addAll(e.data.toList()); pad(e.data.size)
    }
    repeat(1024) { out.add(0) }
    return out.toByteArray()
}

/** Источник для TarReader, отдающий данные маленькими порциями (как распаковщик bzip2). */
internal fun chunkedSource(bytes: ByteArray, chunk: Int = 777): (ByteArray, Int, Int) -> Int {
    var pos = 0
    return { buf, off, len ->
        if (pos >= bytes.size) -1 else {
            val n = minOf(len, chunk, bytes.size - pos)
            bytes.copyInto(buf, off, pos, pos + n)
            pos += n
            n
        }
    }
}

private class MemorySink : TarSink {
    val files = LinkedHashMap<String, ByteArray>()
    val dirs = ArrayList<String>()
    private var name: String? = null
    private var data = ArrayList<Byte>()

    override fun directory(path: String) { dirs.add(path) }
    override fun beginFile(path: String) { name = path; data = ArrayList() }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { for (i in offset until offset + length) data.add(bytes[i]) }
    override fun endFile() { name?.let { files[it] = data.toByteArray() } }
}

class TarReaderTest {

    private fun extract(tar: ByteArray, strip: Int = 1) = MemorySink().also { TarReader(chunkedSource(tar)).extractTo(it, strip) }

    @Test
    fun `voice archive is extracted without the top folder`() {
        val model = ByteArray(70_000) { (it % 251).toByte() }
        val tar = buildTar(
            listOf(
                TarEntry("vits-piper-ru_RU-irina-medium/", type = '5'),
                TarEntry("vits-piper-ru_RU-irina-medium/ru_RU-irina-medium.onnx", model),
                TarEntry("vits-piper-ru_RU-irina-medium/tokens.txt", "_ 0\n^ 1\n".encodeToByteArray()),
                TarEntry("vits-piper-ru_RU-irina-medium/espeak-ng-data/lang/zle/ru", "name russian".encodeToByteArray())
            )
        )
        val sink = extract(tar)

        assertTrue(model.contentEquals(sink.files["ru_RU-irina-medium.onnx"]), "модель должна совпадать побайтно")
        assertEquals("_ 0\n^ 1\n", sink.files["tokens.txt"]?.decodeToString())
        assertEquals("name russian", sink.files["espeak-ng-data/lang/zle/ru"]?.decodeToString())
        assertTrue(sink.dirs.isEmpty(), "корневой каталог архива отбрасывается")
    }

    @Test
    fun `gnu long names are supported`() {
        val longPath = "root/espeak-ng-data/" + "voices/".repeat(14) + "very-long-voice-file-name"
        val sink = extract(buildTar(listOf(TarEntry(longPath, "x".encodeToByteArray()))))
        assertEquals(listOf(longPath.removePrefix("root/")), sink.files.keys.toList())
    }

    @Test
    fun `symlinks are skipped`() {
        val sink = extract(buildTar(listOf(TarEntry("root/link", type = '2'), TarEntry("root/a.txt", "a".encodeToByteArray()))))
        assertEquals(listOf("a.txt"), sink.files.keys.toList())
    }

    @Test
    fun `path traversal is rejected`() {
        assertFailsWith<ArchiveException> { extract(buildTar(listOf(TarEntry("root/../../evil", "x".encodeToByteArray())))) }
        assertFailsWith<ArchiveException> { TarReader.sanitize("/etc/passwd", 0) }
        assertFailsWith<ArchiveException> { TarReader.sanitize("C:\\Windows\\x", 0) }
    }

    @Test
    fun `sanitize normalizes dots and strips components`() {
        assertEquals("b/c", TarReader.sanitize("./a/./b/c", 1))
        assertNull(TarReader.sanitize("a/", 1))
    }

    @Test
    fun `truncated or non-tar data fails loudly`() {
        val tar = buildTar(listOf(TarEntry("root/file", ByteArray(5000))))
        assertFailsWith<ArchiveException> { extract(tar.copyOf(2000)) }
        assertFailsWith<ArchiveException> { extract(ByteArray(600) { 'x'.code.toByte() }) }
    }

    @Test
    fun `pax path record is parsed`() {
        assertEquals("dir/name with spaces.txt", TarReader.parsePaxPath("30 path=dir/name with spaces.txt\n20 mtime=1700000000\n"))
    }

    @Test
    fun `catalog voices point to sherpa-onnx archives`() {
        val irina = PiperVoiceCatalog.byId("vits-piper-ru_RU-irina-medium")
        assertEquals("ru_RU-irina-medium.onnx", irina?.modelFile)
        assertEquals("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium.tar.bz2", irina?.url)
        val russian = PiperVoiceCatalog.voices.filter { it.language == "ru-RU" }.map { it.name }
        assertEquals(listOf("Ирина", "Денис", "Дмитрий", "Руслан"), russian)
        assertEquals(PiperVoiceCatalog.voices.size, PiperVoiceCatalog.voices.map { it.id }.toSet().size)
    }
}
