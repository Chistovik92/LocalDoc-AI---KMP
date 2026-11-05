package com.localdoc.ai.platform

import java.util.TimeZone

actual fun localUtcOffsetMillis(atMillis: Long): Long = TimeZone.getDefault().getOffset(atMillis).toLong()
