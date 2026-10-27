package com.localdoc.ai.domain

import com.localdoc.ai.book.FontLibrary
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Типографика читалки (шрифты, поля, интервалы) и настройки отдельной книги поверх общих. */
class TypographyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old saved settings load with typography defaults`() {
        val old = """{"fontSize":20.0,"theme":"SEPIA","justify":true,"paged":true}"""
        val s = json.decodeFromString(ReaderSettings.serializer(), old)
        assertEquals(20f, s.fontSize)
        assertEquals(ReaderTheme.SEPIA, s.theme)
        assertNull(s.fontFile)
        assertEquals(400, s.fontWeight)
        assertEquals(0f, s.letterSpacing)
        assertEquals(24f, s.margin)
        assertFalse(s.smartJustify)
    }

    @Test
    fun `settings round trip keeps new fields`() {
        val s = ReaderSettings(fontFile = "Literata.ttf", fontWeight = 500, letterSpacing = 0.4f, margin = 40f, smartJustify = true)
        assertEquals(s, json.decodeFromString(ReaderSettings.serializer(), json.encodeToString(ReaderSettings.serializer(), s)))
    }

    @Test
    fun `empty override changes nothing`() {
        val g = ReaderSettings(fontSize = 22f, theme = ReaderTheme.DARK)
        assertEquals(g, g.applyOverride(null))
        assertEquals(g, g.applyOverride(BookOverride()))
        assertTrue(BookOverride().isEmpty)
    }

    @Test
    fun `override wins over global only where it is set`() {
        val g = ReaderSettings(fontSize = 18f, theme = ReaderTheme.LIGHT, margin = 24f, paged = true)
        val e = g.applyOverride(BookOverride(fontSize = 26f, margin = 40f, fontFile = "A.ttf"))
        assertEquals(26f, e.fontSize)
        assertEquals(40f, e.margin)
        assertEquals("A.ttf", e.fontFile)
        assertEquals(ReaderTheme.LIGHT, e.theme)
        assertTrue(e.paged)
    }

    @Test
    fun `editing a book keeps design in the book and the rest in global settings`() {
        val global = ReaderSettings(fontSize = 18f, theme = ReaderTheme.LIGHT)
        val effective = global.applyOverride(BookOverride(theme = ReaderTheme.SEPIA))
        val (newGlobal, override) = global.splitEdit(effective.copy(fontSize = 24f, paged = true, followSpeech = false))
        assertEquals(24f, override.fontSize)
        assertEquals(ReaderTheme.SEPIA, override.theme)
        assertEquals(18f, newGlobal.fontSize)
        assertEquals(ReaderTheme.LIGHT, newGlobal.theme)
        assertTrue(newGlobal.paged)
        assertFalse(newGlobal.followSpeech)
    }

    @Test
    fun `returning a value to the global one drops it from the book`() {
        val global = ReaderSettings(fontSize = 18f)
        val (_, override) = global.splitEdit(global.applyOverride(BookOverride(fontSize = 25f)).copy(fontSize = 18f))
        assertTrue(override.isEmpty)
    }

    @Test
    fun `overrides map survives serialization`() {
        val map = mapOf("book:1" to BookOverride(fontSize = 21f, theme = ReaderTheme.BLACK))
        val serializer = kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), BookOverride.serializer())
        assertEquals(map, json.decodeFromString(serializer, json.encodeToString(serializer, map)))
    }

    @Test
    fun `font files are recognised by signature`() {
        val ttf = byteArrayOf(0, 1, 0, 0) + ByteArray(20)
        val otf = "OTTO".encodeToByteArray() + ByteArray(20)
        val ttc = "ttcf".encodeToByteArray() + ByteArray(20)
        assertTrue(FontLibrary.isFont(ttf) && FontLibrary.isFont(otf) && FontLibrary.isFont(ttc))
        assertFalse(FontLibrary.isFont("%PDF-1.7 not a font".encodeToByteArray()))
        assertFalse(FontLibrary.isFont(ByteArray(4)))
    }

    @Test
    fun `font names cannot escape the storage folder`() {
        assertEquals("Literata.ttf", FontLibrary.safeName("content://x/Literata.ttf"))
        assertTrue(!FontLibrary.safeName("../../etc/passwd").contains('/'))
        assertTrue(!FontLibrary.safeName("..\\..\\evil").contains("\\"))
        assertTrue(!FontLibrary.safeName("..\\..\\evil").startsWith("."))
        assertTrue(FontLibrary.safeName("Шрифт Книги.otf").endsWith(".otf"))
        assertTrue(FontLibrary.safeName("a".repeat(300) + ".ttf").length <= 80)
    }

    @Test
    fun `a book can use the standard font while the global one is custom`() {
        val global = ReaderSettings(fontFile = "A.ttf")
        val (newGlobal, override) = global.splitEdit(global.copy(fontFile = null))
        assertEquals("A.ttf", newGlobal.fontFile)
        assertEquals("", override.fontFile)
        assertNull(global.applyOverride(override).fontFile)
        // Другая книга без настроек по-прежнему видит общий шрифт
        assertEquals("A.ttf", global.applyOverride(null).fontFile)
    }

    @Test
    fun `backup accepts pdf copies and fonts but no other nested paths`() {
        assertTrue(com.localdoc.ai.backup.BackupFormat.isSafePath("pdf/1a2b.pdf"))
        assertTrue(com.localdoc.ai.backup.BackupFormat.isSafePath("fonts/Шрифт Книги.ttf"))
        assertFalse(com.localdoc.ai.backup.BackupFormat.isSafePath("fonts/../x.ttf"))
        assertFalse(com.localdoc.ai.backup.BackupFormat.isSafePath("pdf/a/b.pdf"))
        assertFalse(com.localdoc.ai.backup.BackupFormat.isSafePath("other/x"))
        assertFalse(com.localdoc.ai.backup.BackupFormat.isSafePath("/etc/passwd"))
    }
}
