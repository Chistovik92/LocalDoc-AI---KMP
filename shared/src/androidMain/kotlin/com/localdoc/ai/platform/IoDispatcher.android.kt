package com.localdoc.ai.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO
