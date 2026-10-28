package com.localdoc.ai.domain

import com.localdoc.ai.book.BookFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderChromeTest {
    @Test
    fun `screen pages are on for web and books alike when paged and not translating`() {
        for (format in BookFormat.entries) {
            assertTrue(screenPagesEnabled(paged = true, translating = false, format = format), "$format")
            assertFalse(screenPagesEnabled(paged = false, translating = false, format = format), "$format")
            assertFalse(screenPagesEnabled(paged = true, translating = true, format = format), "$format")
        }
    }

    @Test
    fun `scrolling down hides panels and a long scroll up brings them back`() {
        assertFalse(chromeAfterScroll(true, 0, CHROME_HIDE_SCROLL + 1))
        assertTrue(chromeAfterScroll(true, 0, CHROME_HIDE_SCROLL))
        assertTrue(chromeAfterScroll(false, 5000, 5000 - CHROME_SHOW_SCROLL - 1))
        assertFalse(chromeAfterScroll(false, 5000, 5000 - CHROME_SHOW_SCROLL))
    }

    @Test
    fun `tap toggles panels and page turn hides them`() {
        assertTrue(chromeAfterToggle(false))
        assertFalse(chromeAfterToggle(true))
        assertFalse(chromeAfterPageTurn())
    }

    @Test
    fun `playback start hides panels but pause does not change them`() {
        assertFalse(chromeAfterPlaybackChange(visible = true, playing = true))
        assertTrue(chromeAfterPlaybackChange(visible = true, playing = false))
        assertFalse(chromeAfterPlaybackChange(visible = false, playing = false))
    }

    @Test
    fun `bottom panel with the player follows the top panel`() {
        assertTrue(bottomPanelVisible(true))
        assertFalse(bottomPanelVisible(false))
    }

    @Test
    fun `chapter buttons only in paged listing with navigation`() {
        assertNull(chapterButtons(hasNavigation = false, hasPrevious = true, hasNext = true, pagedListing = true))
        assertNull(chapterButtons(hasNavigation = true, hasPrevious = true, hasNext = true, pagedListing = false))
        assertEquals(ChapterButtons(true, true), chapterButtons(true, hasPrevious = true, hasNext = true, pagedListing = true))
    }

    @Test
    fun `unavailable chapter button is disabled not removed`() {
        assertEquals(ChapterButtons(previousEnabled = false, nextEnabled = true), chapterButtons(true, false, true, true))
        assertEquals(ChapterButtons(previousEnabled = true, nextEnabled = false), chapterButtons(true, true, false, true))
    }

    @Test
    fun `chapter list block only for web books with navigation`() {
        assertNull(chapterListBlock(isWeb = false, hasNavigation = true, hasPrevious = true, hasNext = true, hasContents = true))
        assertNull(chapterListBlock(isWeb = true, hasNavigation = false, hasPrevious = true, hasNext = true, hasContents = true))
        assertEquals(ChapterListBlock(true, true, true), chapterListBlock(true, true, true, true, true))
    }

    @Test
    fun `chapter list block disables unavailable buttons and hides missing contents`() {
        assertEquals(ChapterListBlock(previousEnabled = false, nextEnabled = true, showContents = false), chapterListBlock(true, true, false, true, false))
        assertEquals(ChapterListBlock(previousEnabled = true, nextEnabled = false, showContents = true), chapterListBlock(true, true, true, false, true))
    }

    @Test
    fun `chapter block position in scrolling and translated paging`() {
        assertTrue(chapterBlockOnTop(pagedDisplay = false, page = 5))
        assertTrue(chapterBlockOnBottom(pagedDisplay = false, page = 0, pageCount = 9))
        assertTrue(chapterBlockOnTop(true, 0))
        assertFalse(chapterBlockOnTop(true, 1))
        assertTrue(chapterBlockOnBottom(true, 8, 9))
        assertFalse(chapterBlockOnBottom(true, 7, 9))
    }
}
