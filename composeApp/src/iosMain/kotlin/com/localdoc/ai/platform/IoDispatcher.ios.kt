package com.localdoc.ai.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// На Native IO — расширение kotlinx.coroutines.IO, как и в остальных iOS-файлах
actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO
