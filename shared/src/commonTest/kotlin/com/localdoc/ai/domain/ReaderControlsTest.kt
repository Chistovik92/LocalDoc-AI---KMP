package com.localdoc.ai.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderControlsTest {

    @Test
    fun `edges turn pages and centre opens menu`() {
        assertEquals(TapAction.PREVIOUS, tapAction(50f, 1000f, TapZones.EDGES, 0.3f))
        assertEquals(TapAction.NEXT, tapAction(950f, 1000f, TapZones.EDGES, 0.3f))
        assertEquals(TapAction.MENU, tapAction(500f, 1000f, TapZones.EDGES, 0.3f))
    }

    @Test
    fun `reversed zones swap the edges`() {
        assertEquals(TapAction.NEXT, tapAction(50f, 1000f, TapZones.EDGES_REVERSED, 0.3f))
        assertEquals(TapAction.PREVIOUS, tapAction(950f, 1000f, TapZones.EDGES_REVERSED, 0.3f))
    }

    @Test
    fun `menu only zones and bad width always show the menu`() {
        assertEquals(TapAction.MENU, tapAction(10f, 1000f, TapZones.MENU_ONLY, 0.3f))
        assertEquals(TapAction.MENU, tapAction(10f, 0f, TapZones.EDGES, 0.3f))
    }

    @Test
    fun `zone width is clamped so the centre never disappears`() {
        assertEquals(TapAction.MENU, tapAction(500f, 1000f, TapZones.EDGES, 0.9f))
        assertEquals(TapAction.PREVIOUS, tapAction(90f, 1000f, TapZones.EDGES, 0f))
    }

    @Test
    fun `turning scrolls the list while it can scroll`() {
        assertEquals(PageTurn.SCROLL, pageTurn(true, canScrollList = true, pagedDisplay = true, page = 0, pageCount = 5))
    }

    @Test
    fun `paged mode changes the page at the end of the list`() {
        assertEquals(PageTurn.PAGE, pageTurn(true, false, true, 0, 5))
        assertEquals(PageTurn.PAGE, pageTurn(false, false, true, 3, 5))
    }

    @Test
    fun `no turn past the first or last page and none in scroll mode`() {
        assertEquals(PageTurn.NONE, pageTurn(true, false, true, 4, 5))
        assertEquals(PageTurn.NONE, pageTurn(false, false, true, 0, 5))
        assertEquals(PageTurn.NONE, pageTurn(true, false, false, 0, 5))
    }

    @Test
    fun `swipe direction follows the horizontal movement`() {
        assertEquals(TapAction.NEXT, swipeAction(-200f, 10f, 100f))
        assertEquals(TapAction.PREVIOUS, swipeAction(200f, -10f, 100f))
    }

    @Test
    fun `short or vertical swipes are ignored`() {
        assertNull(swipeAction(-50f, 0f, 100f))
        assertNull(swipeAction(-200f, 150f, 100f))
    }

    @Test
    fun `pinch scales the font within limits`() {
        assertEquals(24f, pinchFontSize(18f, 4f / 3f), 0.001f)
        assertEquals(MAX_READER_FONT_SIZE, pinchFontSize(30f, 2f))
        assertEquals(MIN_READER_FONT_SIZE, pinchFontSize(14f, 0.1f))
        assertEquals(18f, pinchFontSize(18f, 0f))
    }

    @Test
    fun `dragging up brightens and down dims within limits`() {
        assertEquals(0.6f, brightnessAfterDrag(0.5f, -100f, 1000f), 0.001f)
        assertEquals(0.4f, brightnessAfterDrag(0.5f, 100f, 1000f), 0.001f)
        assertEquals(1f, brightnessAfterDrag(0.9f, -900f, 1000f))
        assertEquals(MIN_BRIGHTNESS, brightnessAfterDrag(0.1f, 900f, 1000f))
        assertEquals(0.5f, brightnessAfterDrag(0.5f, 100f, 0f))
    }

    @Test
    fun `auto scroll covers speed times line height over time`() {
        // 60 строк в минуту — строка в секунду
        assertEquals(30f, autoScrollDelta(60, 30f, 1000), 0.001f)
        assertEquals(15f, autoScrollDelta(60, 30f, 500), 0.001f)
    }

    @Test
    fun `auto scroll speed is clamped and degenerate input gives zero`() {
        assertEquals(autoScrollDelta(MAX_AUTO_SCROLL_LPM, 30f, 1000), autoScrollDelta(10_000, 30f, 1000))
        assertEquals(autoScrollDelta(MIN_AUTO_SCROLL_LPM, 30f, 1000), autoScrollDelta(1, 30f, 1000))
        assertEquals(0f, autoScrollDelta(0, 30f, 1000))
        assertEquals(0f, autoScrollDelta(60, 0f, 1000))
        assertEquals(0f, autoScrollDelta(60, 30f, 0))
    }

    @Test
    fun `page animation is off for e-ink and reduced motion`() {
        assertTrue(pageAnimationEnabled(true, eInk = false, reducedMotion = false))
        assertFalse(pageAnimationEnabled(false, false, false))
        assertFalse(pageAnimationEnabled(true, eInk = true, reducedMotion = false))
        assertFalse(pageAnimationEnabled(true, eInk = false, reducedMotion = true))
    }

    @Test
    fun `full refresh happens every N turns`() {
        assertFalse(needsFullRefresh(0, 6))
        assertFalse(needsFullRefresh(5, 6))
        assertTrue(needsFullRefresh(6, 6))
        assertTrue(needsFullRefresh(12, 6))
        assertFalse(needsFullRefresh(6, 0))
    }

    @Test
    fun `old settings json without new fields keeps defaults`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(ReaderSettings.serializer(), """{"fontSize":20.0,"paged":true}""")
        assertEquals(20f, old.fontSize)
        assertTrue(old.paged)
        assertEquals(TapZones.EDGES, old.tapZones)
        assertFalse(old.eInk)
        assertNull(old.brightness)
        assertEquals(OrientationLock.AUTO, old.orientation)
    }
}
