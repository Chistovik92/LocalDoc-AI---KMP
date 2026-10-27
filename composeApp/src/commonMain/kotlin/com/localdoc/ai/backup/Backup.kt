package com.localdoc.ai.backup

import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.ZipArchive
import com.localdoc.ai.book.export.ZipWriter
import com.localdoc.ai.net.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Доступ резервной копии к данным приложения: настройки (KV) и файлы книг. Пароли сайтов сюда не входят. */
interface BackupStorage {
    fun getValue(key: String): String?
    fun putValue(key: String, value: String)
    fun removeValue(key: String)
    fun readFile(name: String): ByteArray?
    fun writeFile(name: String, bytes: ByteArray)
    fun listFiles(prefix: String): List<String>
    fun deleteFiles(prefix: String)
}

/** Описание содержимого копии — файл `backup.json` в архиве. */
@Serializable
data class BackupManifest(
    val format: String = BackupFormat.ID,
    /** Версия формата архива; приложение читает копии этой и более старых версий. */
    val version: Int,
    val appVersion: String = "",
    val createdAt: Long = 0,
    val books: Int = 0,
    val keys: List<String> = emptyList(),
    val files: List<String> = emptyList()
)

object BackupFormat {
    const val ID = "localdoc-backup"

    /** Текущая версия формата архива. Увеличивается при несовместимом изменении состава или разметки. */
    const val VERSION = 1

    const val MANIFEST = "backup.json"
    const val KV_DIR = "kv/"
    const val FILES_DIR = "files/"
    const val EXTENSION = "ldbackup"
    const val MIME = "application/zip"

    const val KEY_LIBRARY = "library_v1"
    const val KEY_READING_STATS = "reading_stats_v1"

    /**
     * Что сохраняется из настроек: библиотека с прогрессом чтения, закладки, цитаты и заметки, словарь и
     * настройки читалки, озвучки и перевода. Входы на сайты (пароли и cookie) живут в защищённом хранилище и
     * в копию не попадают, как и скачанные модели и голоса, кэш картинок и индексы — их можно получить заново.
     */
    val KV_KEYS = listOf(
        KEY_LIBRARY, "bookmarks_v1", "annotations_v1", "dictionary_v1",
        "reader_settings", "voice_settings", "translation_settings",
        // Необязательная запись: в копиях без неё статистики просто нет, а старые версии приложения её пропускают
        KEY_READING_STATS
    )

    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /** Имя файла из архива допустимо, только если это простое имя без путей: защита от выхода из папки приложения. */
    fun isSafeName(name: String) = SAFE_NAME.matches(name) && ".." !in name

    fun suggestedName(createdAt: Long) = "LocalDoc-копия-${isoDate(createdAt)}.$EXTENSION"

    /** Дата по UTC в виде 2026-10-23 (алгоритм «civil from days» — без зависимости от платформенных календарей). */
    fun isoDate(millis: Long): String {
        val z = millis.floorDiv(DAY_MS) + 719_468
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private const val DAY_MS = 86_400_000L
}

/** Итог проверки файла копии без каких-либо изменений в данных приложения. */
sealed interface BackupCheck {
    data class Ok(val manifest: BackupManifest) : BackupCheck

    /** Это не копия LocalDoc, файл повреждён или чего-то не хватает. */
    data class Invalid(val reason: String) : BackupCheck

    /** Копия создана более новой версией приложения и может содержать то, чего эта версия не понимает. */
    data class TooNew(val version: Int, val appVersion: String) : BackupCheck
}

sealed interface RestoreResult {
    data class Restored(val manifest: BackupManifest) : RestoreResult
    data class Failed(val message: String) : RestoreResult
}

/** Создание и восстановление резервной копии библиотеки и настроек одним файлом. */
class BackupService(
    private val storage: BackupStorage,
    private val appVersion: String,
    private val clock: () -> Long = ::nowMillis
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val entries = ListSerializer(BookEntry.serializer())

    /** Архив: manifest, значения настроек (`kv/…`) и файлы книг с картинками (`files/…`). */
    fun create(): ByteArray {
        val library = libraryEntries(storage.getValue(BackupFormat.KEY_LIBRARY))
        val fileNames = library.flatMap { bookFiles(it.id) }.distinct().filter { storage.readFile(it) != null }
        val keys = BackupFormat.KV_KEYS.filter { storage.getValue(it) != null }
        val manifest = BackupManifest(
            version = BackupFormat.VERSION, appVersion = appVersion, createdAt = clock(),
            books = library.size, keys = keys, files = fileNames
        )
        val zip = ZipWriter()
        zip.add(BackupFormat.MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest))
        for (key in keys) zip.add(BackupFormat.KV_DIR + key, storage.getValue(key).orEmpty())
        for (name in fileNames) storage.readFile(name)?.let { zip.add(BackupFormat.FILES_DIR + name, it) }
        return zip.finish()
    }

    /** Проверяет файл: формат, версию, наличие всех записей и файлов книг. Ничего не меняет. */
    fun inspect(bytes: ByteArray): BackupCheck = check(bytes).first

    /** Заменяет библиотеку и настройки данными копии. Сначала файл проверяется целиком — при ошибке данные не тронуты. */
    fun restore(bytes: ByteArray): RestoreResult {
        val (verdict, archive) = check(bytes)
        return when (verdict) {
            is BackupCheck.Invalid -> RestoreResult.Failed(verdict.reason)
            is BackupCheck.TooNew -> RestoreResult.Failed(tooNewMessage(verdict))
            is BackupCheck.Ok -> try {
                apply(verdict.manifest, requireNotNull(archive))
                RestoreResult.Restored(verdict.manifest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestoreResult.Failed("Не удалось восстановить: ${e.message ?: "ошибка записи"}")
            }
        }
    }

    fun tooNewMessage(check: BackupCheck.TooNew) =
        "Копия создана более новой версией LocalDoc (формат ${check.version}" +
            check.appVersion.takeIf { it.isNotBlank() }?.let { ", приложение $it" }.orEmpty() + "). Обновите приложение и повторите."

    private fun check(bytes: ByteArray): Pair<BackupCheck, ZipArchive?> {
        val notBackup = BackupCheck.Invalid("Это не файл резервной копии LocalDoc") to null
        val archive = try {
            ZipArchive(bytes)
        } catch (e: Exception) {
            return notBackup
        }
        val manifest = try {
            archive.readText(BackupFormat.MANIFEST)?.let { json.decodeFromString(BackupManifest.serializer(), it) }
        } catch (e: Exception) {
            null
        }
        if (manifest == null || manifest.format != BackupFormat.ID) return notBackup
        if (manifest.version > BackupFormat.VERSION) return BackupCheck.TooNew(manifest.version, manifest.appVersion) to null
        if (manifest.version < 1) return BackupCheck.Invalid("Неизвестная версия формата копии: ${manifest.version}") to null

        val problem = validateContents(manifest, archive)
        return if (problem != null) BackupCheck.Invalid(problem) to null else BackupCheck.Ok(manifest) to archive
    }

    private fun validateContents(manifest: BackupManifest, archive: ZipArchive): String? {
        try {
            for (key in manifest.keys.filter { it in BackupFormat.KV_KEYS }) {
                val value = archive.readText(BackupFormat.KV_DIR + key) ?: return "В копии нет данных «$key» — файл повреждён"
                json.parseToJsonElement(value)
            }
            // Библиотека разбирается строго: значение неверной схемы (например «{}») не должно стереть текущую
            val library = archive.readText(BackupFormat.KV_DIR + BackupFormat.KEY_LIBRARY)
                ?.let { json.decodeFromString(entries, it) }.orEmpty()
            for (entry in library) {
                if (!BackupFormat.isSafeName(entry.id)) return "В копии неверный идентификатор книги — файл повреждён"
                val bytes = archive.read(BackupFormat.FILES_DIR + bookFile(entry.id))
                    ?: return "В копии нет текста книги «${entry.title}» — файл повреждён или обрезан"
                // Контрольные суммы ZIP не проверяются, поэтому текст книги разбирается целиком до любой записи
                json.decodeFromString(Book.serializer(), bytes.decodeToString())
            }
        } catch (e: Exception) {
            return "Файл копии повреждён: ${e.message ?: "ошибка чтения"}"
        }
        return null
    }

    private fun apply(manifest: BackupManifest, archive: ZipArchive) {
        // Всё нужное читается до первой записи: сбой чтения не оставит приложение с половиной данных
        val values = manifest.keys.filter { it in BackupFormat.KV_KEYS }
            .associateWith { requireNotNull(archive.readText(BackupFormat.KV_DIR + it)) }
        val restoredFiles = archive.names
            .filter { it.startsWith(BackupFormat.FILES_DIR) }
            .map { it.removePrefix(BackupFormat.FILES_DIR) }
            .filter(BackupFormat::isSafeName)
            .associateWith { requireNotNull(archive.read(BackupFormat.FILES_DIR + it)) }

        libraryEntries(storage.getValue(BackupFormat.KEY_LIBRARY)).forEach {
            storage.deleteFiles(bookFile(it.id))
            storage.deleteFiles(imagePrefix(it.id))
        }
        restoredFiles.forEach { (name, data) -> storage.writeFile(name, data) }
        for (key in BackupFormat.KV_KEYS) {
            val value = values[key]
            if (value != null) storage.putValue(key, value) else storage.removeValue(key)
        }
    }

    private fun libraryEntries(raw: String?): List<BookEntry> =
        raw?.let { runCatching { json.decodeFromString(entries, it) }.getOrNull() }.orEmpty()

    private fun bookFiles(id: String): List<String> = listOf(bookFile(id)) + storage.listFiles(imagePrefix(id))

    private fun bookFile(id: String) = "$id.json"

    private fun imagePrefix(id: String) = "$id.img."
}
