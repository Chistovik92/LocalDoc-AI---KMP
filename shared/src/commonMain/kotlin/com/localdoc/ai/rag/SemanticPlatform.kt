package com.localdoc.ai.rag

/** Суммарная память устройства, МБ (0 — неизвестно). */
expect fun totalDeviceMemoryMb(): Long

/** true — на платформе есть ONNX Runtime для [createOnnxTextEncoder]. */
expect val onnxRuntimeAvailable: Boolean

/** Открывает ONNX-модель; бросает исключение, если файл повреждён или платформа не поддерживается. */
expect fun createOnnxTextEncoder(modelPath: String): OnnxTextEncoder

/** Читает файл блоками; false — файл не открылся. Для проверки хеша без загрузки модели в память. */
expect fun readFileChunks(path: String, chunkSize: Int, onChunk: (buffer: ByteArray, length: Int) -> Unit): Boolean
