package com.localdoc.ai.platform

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.AVKit.AVRoutePickerView

/** Системная кнопка AirPlay: нажатие открывает список колонок, телевизоров и наушников. Не проверялось на устройстве. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AudioRouteButton(modifier: Modifier) {
    UIKitView(
        factory = { AVRoutePickerView(frame = CGRectZero.readValue()) },
        modifier = modifier.size(48.dp)
    )
}
