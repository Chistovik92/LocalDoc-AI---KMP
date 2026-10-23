package com.localdoc.ai.platform

import kotlinx.coroutines.CoroutineDispatcher

/** Диспетчер для файловой работы: в общем коде `Dispatchers.IO` недоступен, на обеих платформах это он. */
expect val IoDispatcher: CoroutineDispatcher
