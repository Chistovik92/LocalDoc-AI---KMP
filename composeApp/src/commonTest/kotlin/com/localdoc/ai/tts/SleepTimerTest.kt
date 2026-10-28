package com.localdoc.ai.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerTest {

    private var now = 1_000_000L
    private val timer = SleepTimer { now }

    @Test
    fun `timer is idle until started`() {
        assertNull(timer.state.value.mode)
        assertEquals(SleepTick.Idle, timer.tick())
    }

    @Test
    fun `countdown reports remaining time and keeps full volume before the fade window`() {
        timer.startAfter(10)
        now += 60_000
        val tick = timer.tick() as SleepTick.Running
        assertEquals(9 * 60_000L, timer.state.value.remainingMs)
        assertEquals(1f, tick.volume)
    }

    @Test
    fun `volume falls smoothly inside the fade window and never reaches zero before expiry`() {
        timer.startAfter(1)
        now += 60_000 - SleepFade.FADE_MS / 2
        val half = (timer.tick() as SleepTick.Running).volume
        assertTrue(half in 0.4f..0.6f, "в середине затухания громкость около половины: $half")

        now += SleepFade.FADE_MS / 2 - 1_000
        val last = (timer.tick() as SleepTick.Running).volume
        assertTrue(last < half && last > 0f)
    }

    @Test
    fun `expires exactly once and returns to idle`() {
        timer.startAfter(1)
        now += 60_000
        assertEquals(SleepTick.Expired, timer.tick())
        assertNull(timer.state.value.mode)
        assertEquals(SleepTick.Idle, timer.tick())
    }

    @Test
    fun `cancel stops the countdown`() {
        timer.startAfter(5)
        timer.cancel()
        now += 10 * 60_000
        assertEquals(SleepTick.Idle, timer.tick())
    }

    @Test
    fun `extend adds minutes to a running countdown`() {
        timer.startAfter(1)
        timer.extend(5)
        now += 2 * 60_000
        assertTrue(timer.tick() is SleepTick.Running)
        assertEquals(4 * 60_000L, timer.state.value.remainingMs)
    }

    @Test
    fun `zero or negative minutes do not start the timer`() {
        timer.startAfter(0)
        assertNull(timer.state.value.mode)
        timer.startAfter(-3)
        assertNull(timer.state.value.mode)
    }

    @Test
    fun `chapter end mode has no deadline and fades by sentences left`() {
        timer.startAtChapterEnd()
        assertEquals(SleepMode.ChapterEnd, timer.state.value.mode)
        now += 3 * 3_600_000L
        assertTrue(timer.tick(sentencesLeft = 20) is SleepTick.Running)
        assertEquals(1f, (timer.tick(sentencesLeft = 20) as SleepTick.Running).volume)
        val near = (timer.tick(sentencesLeft = 1) as SleepTick.Running).volume
        assertTrue(near < 1f && near > 0f)
        val fewer = (timer.tick(sentencesLeft = 3) as SleepTick.Running).volume
        assertTrue(fewer > near, "чем ближе конец, тем тише")
    }

    @Test
    fun `chapter end is consumed by the player stop`() {
        timer.startAtChapterEnd()
        assertTrue(timer.state.value.mode == SleepMode.ChapterEnd)
        timer.finish()
        assertNull(timer.state.value.mode)
        assertFalse(timer.tick() is SleepTick.Running)
    }
}
