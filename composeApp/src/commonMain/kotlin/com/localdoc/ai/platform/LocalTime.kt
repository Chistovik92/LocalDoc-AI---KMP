package com.localdoc.ai.platform

/** Смещение местного времени устройства от UTC в миллисекундах на момент [atMillis] (с учётом летнего времени). */
expect fun localUtcOffsetMillis(atMillis: Long): Long
