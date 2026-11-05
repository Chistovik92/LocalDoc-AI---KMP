package com.localdoc.ai.ui.reader

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderHotkeysTest {
    @Test
    fun `стрелки и Page листают`() {
        assertEquals(ReaderHotkey.NEXT, hotkeyFor(Key.DirectionRight, shift = false, ctrl = false))
        assertEquals(ReaderHotkey.NEXT, hotkeyFor(Key.PageDown, shift = false, ctrl = false))
        assertEquals(ReaderHotkey.PREVIOUS, hotkeyFor(Key.DirectionLeft, shift = false, ctrl = false))
        assertEquals(ReaderHotkey.PREVIOUS, hotkeyFor(Key.PageUp, shift = false, ctrl = false))
    }

    @Test
    fun `пробел листает вперёд, с Shift — назад`() {
        assertEquals(ReaderHotkey.NEXT, hotkeyFor(Key.Spacebar, shift = false, ctrl = false))
        assertEquals(ReaderHotkey.PREVIOUS, hotkeyFor(Key.Spacebar, shift = true, ctrl = false))
    }

    @Test
    fun `буквы открывают оглавление, закладку и озвучку`() {
        assertEquals(ReaderHotkey.CONTENTS, hotkeyFor(Key.T, shift = false, ctrl = false))
        assertEquals(ReaderHotkey.BOOKMARK, hotkeyFor(Key.B, shift = false, ctrl = false))
        assertEquals(ReaderHotkey.PLAY_PAUSE, hotkeyFor(Key.P, shift = false, ctrl = false))
    }

    @Test
    fun `с Ctrl и на прочих клавишах ничего не перехватывается`() {
        assertNull(hotkeyFor(Key.DirectionRight, shift = false, ctrl = true))
        assertNull(hotkeyFor(Key.Q, shift = false, ctrl = false))
    }
}
