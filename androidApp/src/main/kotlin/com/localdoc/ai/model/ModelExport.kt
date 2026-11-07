package com.localdoc.ai.model

import java.io.File

/** Модель, доступная внешнему приложению только для чтения. */
internal data class ExportedModel(
    val id: String,
    val name: String,
    val url: String,
    val file: File
)

/** Общие для ContentProvider/FileProvider проверки: никаких произвольных путей наружу. */
internal class ModelExport(rootDir: File, models: List<ExportedModel>) {
    private val root = rootDir.canonicalFile
    private val modelsByName = models.associateBy { "${it.id}.gguf" }

    fun downloaded(): List<ExportedModel> = modelsByName.values.filter { isDownloaded(it.file) }

    fun resolve(fileName: String): ExportedModel? {
        // Только точное имя из каталога: разделители, ".." и symlink не дают выйти из models/.
        val model = modelsByName[fileName] ?: return null
        val file = model.file.canonicalFile
        if (file.parentFile != root || !isDownloaded(file)) return null
        return model.copy(file = file)
    }

    private fun isDownloaded(file: File): Boolean {
        val canonical = file.canonicalFile
        return canonical.parentFile == root && canonical.isFile && canonical.length() > 0L
    }
}
