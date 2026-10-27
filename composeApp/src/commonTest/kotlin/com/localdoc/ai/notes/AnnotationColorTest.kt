package com.localdoc.ai.notes

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationColorTest {

    private val entry = BookEntry("b1", "Тест", "", BookFormat.EPUB, "uri", 10)
    private val anchor = TextAnchor(block = 1, start = 0, end = 5, quote = "слово")

    @Test
    fun `new quote is yellow by default and colour can be chosen`() {
        val repo = AnnotationRepository({ null }, { })
        val plain = repo.add(entry, anchor)
        val green = repo.add(entry, anchor, "заметка", AnnotationColor.GREEN)
        assertEquals(AnnotationColor.YELLOW, plain.color)
        assertEquals(AnnotationColor.GREEN, green.color)
    }

    @Test
    fun `colour is updated and persisted`() {
        var stored: String? = null
        val repo = AnnotationRepository({ stored }, { stored = it })
        val a = repo.add(entry, anchor)
        repo.updateColor(a.id, AnnotationColor.PINK)
        assertEquals(AnnotationColor.PINK, AnnotationRepository({ stored }, { }).all.value.single().color)
    }

    @Test
    fun `quotes saved before colours existed read as yellow`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """[{"id":"1","entryId":"b1","bookTitle":"Т","format":"EPUB","anchor":{"block":0}}]"""
        val list = json.decodeFromString(ListSerializer(Annotation.serializer()), old)
        assertEquals(AnnotationColor.YELLOW, list.single().color)
    }

    @Test
    fun `tap offset finds the mark that covers it`() {
        val marks = listOf(
            TextMark(0 until 5, AnnotationColor.YELLOW, "a"),
            TextMark(10 until 20, AnnotationColor.BLUE, "b")
        )
        assertEquals("a", marks.markAt(0)?.annotationId)
        assertEquals("b", marks.markAt(19)?.annotationId)
        assertNull(marks.markAt(5))
        assertNull(marks.markAt(20))
    }

    @Test
    fun `overlapping marks resolve to the latest one`() {
        val marks = listOf(
            TextMark(0 until 10, AnnotationColor.YELLOW, "first"),
            TextMark(5 until 8, AnnotationColor.GREEN, "second")
        )
        assertEquals("second", marks.markAt(6)?.annotationId)
        assertEquals("first", marks.markAt(2)?.annotationId)
    }
}
