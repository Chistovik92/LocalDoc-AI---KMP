package com.localdoc.ai.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Кнопка «Куда играть»: системный выбор устройства вывода звука озвучки (колонка, наушники, телевизор).
 * iOS — системный список AirPlay (`AVRoutePickerView`); Android — системная панель вывода звука
 * (Bluetooth, подключённые устройства и колонки с Google Cast, если система их показывает).
 * Своих сетевых протоколов приложение не использует: звук направляет сама система.
 */
@Composable
expect fun AudioRouteButton(modifier: Modifier = Modifier)
