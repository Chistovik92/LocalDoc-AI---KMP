package com.localdoc.ai.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// На Native `Dispatchers.IO` скрыт (internal), а Default — многопоточный пул
actual val IoDispatcher: CoroutineDispatcher = Dispatchers.Default
