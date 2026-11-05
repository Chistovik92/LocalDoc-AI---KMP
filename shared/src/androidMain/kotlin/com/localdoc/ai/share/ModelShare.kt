package com.localdoc.ai.share

import java.io.File

/**
 * Чистая логика публикации GGUF-моделей наружу через [ModelProvider].
 * Без зависимостей от Android: провайдер оборачивает её, unit-тесты проверяют напрямую.
 *
 * Модели лежат в `getExternalFilesDir(null)/models/<id>.gguf` (см. `ModelDownloader`),
 * `id` — стабильный ключ из `ModelCatalog`. Провайдер отдаёт только `.gguf` внутри
 * этого каталога и ничего больше: книги, пароли и cookie хранятся в других местах.
 */
object ModelShare {

    /** Расширение файла модели. */
    const val EXTENSION = ".gguf"

    /**
     * Резолвит [id] модели в файл внутри [modelsDir].
     *
     * Возвращает `null`, если id пуст, содержит разделители пути (`/`, `\`) или точки
     * обхода (`.`, `..`), файл отсутствует, либо его канонический путь выходит за пределы
     * каталога (симлинк/жёсткая ссылка наружу). Канонический путь — последняя линия защиты.
     */
    fun resolve(modelsDir: File, id: String): File? {
        if (id.isBlank()) return null
        if (id == "." || id == "..") return null
        if (id.any { it == '/' || it == '\\' }) return null

        val file = File(modelsDir, id + EXTENSION)
        if (!file.isFile) return null
        return if (isInside(modelsDir, file)) file else null
    }

    /** Скачанные GGUF-модели в каталоге, отсортированные по имени файла. */
    fun listDownloaded(modelsDir: File): List<File> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun isInside(dir: File, file: File): Boolean {
        val dirPath = dir.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return filePath.startsWith(dirPath)
    }
}
