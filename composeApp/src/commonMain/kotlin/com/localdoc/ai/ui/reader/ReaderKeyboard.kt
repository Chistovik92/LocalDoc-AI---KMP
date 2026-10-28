package com.localdoc.ai.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Действие читалки, вызываемое с клавиатуры (планшеты, Bluetooth-клавиатуры). */
internal enum class ReaderHotkey { NEXT, PREVIOUS, CONTENTS, BOOKMARK, PLAY_PAUSE }

/**
 * Соответствие клавиши действию: стрелки вправо/влево, Page Down/Up и пробел (Shift+пробел — назад) листают,
 * T — оглавление, B — закладка, P — пауза и продолжение озвучки. С Ctrl клавиши не перехватываются.
 */
internal fun hotkeyFor(key: Key, shift: Boolean, ctrl: Boolean): ReaderHotkey? {
    if (ctrl) return null
    return when (key) {
        Key.DirectionRight, Key.PageDown -> ReaderHotkey.NEXT
        Key.DirectionLeft, Key.PageUp -> ReaderHotkey.PREVIOUS
        Key.Spacebar -> if (shift) ReaderHotkey.PREVIOUS else ReaderHotkey.NEXT
        Key.T -> ReaderHotkey.CONTENTS
        Key.B -> ReaderHotkey.BOOKMARK
        Key.P -> ReaderHotkey.PLAY_PAUSE
        else -> null
    }
}

/** Горячие клавиши читалки: берёт фокус при показе и обрабатывает нажатия раньше вложенного содержимого. */
@Composable
internal fun Modifier.readerHotkeys(onHotkey: (ReaderHotkey) -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val action = hotkeyFor(event.key, event.isShiftPressed, event.isCtrlPressed) ?: return@onPreviewKeyEvent false
            onHotkey(action)
            true
        }
}
