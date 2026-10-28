package com.localdoc.ai.book

import androidx.compose.ui.text.font.FontFamily
import com.localdoc.ai.platform.FileAccess
import com.localdoc.ai.platform.IoDispatcher
import kotlinx.coroutines.withContext
import com.localdoc.ai.platform.fontFamilyFromBytes

/** Свои шрифты читалки: файлы TTF/OTF лежат в хранилище приложения (`fonts/`), выбор — по имени файла. */
class FontLibrary(private val files: FileAccess) {

    private val families = HashMap<String, FontFamily?>()

    /** Копирует выбранный файл в хранилище; возвращает имя шрифта или null, если это не шрифт или он слишком велик. */
    suspend fun import(pathOrUri: String): String? {
        val bytes = runCatching { files.readBytes(pathOrUri) }.getOrNull() ?: return null
        if (bytes.size > MAX_FONT_BYTES || !isFont(bytes)) return null
        val name = safeName(files.displayName(pathOrUri) ?: pathOrUri)
        files.writeAppFile(PREFIX + name, bytes)
        families.remove(name)
        return name
    }

    fun list(): List<String> = files.listAppFiles(PREFIX).map { it.removePrefix(PREFIX) }

    fun remove(name: String) {
        files.deleteAppFile(PREFIX + name)
        families.remove(name)
    }

    /** Уже загруженное семейство (без чтения файла — безопасно вызывать при отрисовке); null — ещё не загружено. */
    fun cached(name: String?): FontFamily? = name?.let { families[it] }

    /** Загружает семейство в фоне; null — файл исчез или повреждён (тогда читалка берёт обычный шрифт). */
    suspend fun load(name: String?): FontFamily? {
        if (name == null) return null
        if (name in families) return families[name]
        return withContext(IoDispatcher) {
            files.readAppFile(PREFIX + name)?.takeIf(::isFont)?.let { fontFamilyFromBytes(name, it) }
        }.also { families[name] = it }
    }

    companion object {
        const val PREFIX = "fonts/"
        const val MAX_FONT_BYTES = 30 * 1024 * 1024
        private const val BACKSLASH = '\\'

        /** Сигнатура sfnt: TrueType (00 01 00 00, `true`), OpenType с CFF (`OTTO`) или коллекция (`ttcf`). */
        fun isFont(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val head = bytes.decodeToString(0, 4)
            val trueType = bytes[0].toInt() == 0 && bytes[1].toInt() == 1 && bytes[2].toInt() == 0 && bytes[3].toInt() == 0
            return trueType || head == "OTTO" || head == "true" || head == "ttcf"
        }

        /** Имя файла без путей и странных символов: оно становится частью пути в хранилище. */
        fun safeName(display: String): String {
            val base = display.substringAfterLast('/').substringAfterLast(BACKSLASH)
            val clean = base.map { if (it.isLetterOrDigit() || it in "._- ") it else '_' }.joinToString("").trim().trimStart('.')
            val withExt = if (clean.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") || it.endsWith(".ttc") }) clean else "$clean.ttf"
            return withExt.take(80)
        }
    }
}
