package com.localdoc.ai.sync

import com.localdoc.ai.net.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Один вид синхронизируемых данных (прогресс, закладки…) — мост между хранилищем приложения и записями. */
interface SyncCollection {
    val kind: String

    /** true — исчезновение записи здесь значит «удалено пользователем» и уходит в облако надгробием. */
    val tracksDeletion: Boolean

    /** Ключ → полезная нагрузка (JSON) для всего, что есть на устройстве сейчас. */
    fun read(): Map<String, String>

    /** Применяет изменения из облака; возвращает ключи, которые пока применить нельзя (например, нет книги). */
    fun apply(upserts: Map<String, String>, removals: Set<String>): Set<String>
}

enum class DivergenceReason { CLOUD_ROLLED_BACK, MASS_DELETE }

/** Что делать, когда синхронизация остановилась в безопасном режиме. */
enum class DivergenceChoice {
    /** Всё равно слить по правилу «последняя правка». */
    MERGE,

    /** Облако заменить данными этого устройства. */
    KEEP_LOCAL,

    /** Данные этого устройства заменить облаком. */
    USE_REMOTE
}

sealed interface SyncOutcome {
    data class Done(
        val pulled: Int,
        val pushed: Int,
        val conflicts: Int,
        val deferred: Int,
        val failedKinds: List<String> = emptyList()
    ) : SyncOutcome

    /** Безопасный режим: ничего не записано ни здесь, ни в облаке, пока пользователь не выберет [DivergenceChoice]. */
    data class Diverged(val reason: DivergenceReason, val message: String) : SyncOutcome

    /** Пароль шифрования не подошёл или файл в облаке повреждён. */
    data class WrongPassword(val message: String) : SyncOutcome

    /** Файл в облаке создан более новой версией. */
    data class TooNew(val version: Int) : SyncOutcome

    /** Нет связи или сервер отказал; данные на устройстве не тронуты. */
    data class Failed(val message: String) : SyncOutcome
}

/**
 * Синхронизация: скачать → расшифровать → слить с локальными правками → отправить → применить. Локальные
 * правки находятся сравнением текущих данных с общей базой ([SyncState.records]), поэтому репозитории
 * приложения ни о чём не знают. Порядок «сначала облако, потом устройство» делает обрыв связи безопасным.
 */
class SyncEngine(
    private val collections: List<SyncCollection>,
    private val loadState: () -> String?,
    private val saveState: (String) -> Unit,
    private val clock: () -> Long = ::nowMillis,
    /** Вызывается перед первой записью в данные устройства (например, автоматическая резервная копия). */
    private val safetyCopy: () -> Unit = {},
    /** Виды данных, изменённые синхронизацией: пора перечитать репозитории. */
    private val onApplied: (Set<String>) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val byKind = collections.associateBy { it.kind }

    suspend fun sync(transport: SyncTransport, cipher: SyncCipher, choice: DivergenceChoice? = null): SyncOutcome =
        lock.withLock {
            var attempt = 0
            while (true) {
                try {
                    return@withLock runOnce(transport, cipher, choice)
                } catch (e: UploadConflictException) {
                    if (++attempt >= MAX_ATTEMPTS) {
                        return@withLock SyncOutcome.Failed("Облако постоянно меняется другим устройством — повторите позже")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SyncTransportException) {
                    return@withLock SyncOutcome.Failed(e.message ?: "Ошибка связи")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    fun state(): SyncState = readState()

    private suspend fun runOnce(transport: SyncTransport, cipher: SyncCipher, choice: DivergenceChoice?): SyncOutcome {
        val state = readState().let { if (it.deviceId.isEmpty()) it.copy(deviceId = Random.nextLong().toULong().toString(16)) else it }
        val now = clock()

        val remoteFile = transport.download()
        val remoteDoc: SyncDocument? = if (remoteFile == null) null else {
            val plain = try {
                cipher.open(remoteFile.bytes)
            } catch (e: SyncDecryptException) {
                return SyncOutcome.WrongPassword(e.message ?: "Не удалось расшифровать")
            }
            val doc = try {
                json.decodeFromString(SyncDocument.serializer(), plain.decodeToString())
            } catch (e: Exception) {
                return SyncOutcome.WrongPassword("Файл синхронизации повреждён")
            }
            if (doc.format != SyncFormat.ID) return SyncOutcome.WrongPassword("Это не файл синхронизации LocalDoc")
            if (doc.version > SyncFormat.VERSION) return SyncOutcome.TooNew(doc.version)
            doc
        }

        if (remoteDoc != null && choice == null && remoteDoc.revision < state.revision) {
            return SyncOutcome.Diverged(
                DivergenceReason.CLOUD_ROLLED_BACK,
                "Данные в облаке старее тех, что уже были синхронизированы (ревизия ${remoteDoc.revision} < ${state.revision}). " +
                    "Возможно, облако восстановили из старой копии."
            )
        }

        val current = HashMap<String, String>()
        for (c in collections) c.read().forEach { (key, data) -> current["${c.kind}/$key"] = data }
        val baseMap = state.records.associateBy { it.id }
        val deferred = state.deferred.toSet()
        // «Оставить моё»: все записи устройства получают свежее время, чтобы перебить надгробия других устройств
        val local = detectLocal(current, baseMap, deferred, state.deviceId, now, fresh = choice == DivergenceChoice.KEEP_LOCAL)

        val remoteMap = if (choice == DivergenceChoice.KEEP_LOCAL) emptyMap() else remoteDoc?.records.orEmpty().associateBy { it.id }
        val merge = SyncMerge.merge(
            local = if (choice == DivergenceChoice.USE_REMOTE) emptyMap() else local,
            remote = remoteMap, base = baseMap, now = now
        )

        val removalsLocal = if (choice == DivergenceChoice.USE_REMOTE) {
            current.keys.filter { it !in remoteMap && byKind[it.substringBefore('/')]?.tracksDeletion == true }.toSet()
        } else {
            merge.toLocal.filter { it.deleted && it.id in current }.map { it.id }.toSet()
        }

        if (choice == null && removalsLocal.size >= MASS_DELETE_MIN && removalsLocal.size * 2 > current.size) {
            return SyncOutcome.Diverged(
                DivergenceReason.MASS_DELETE,
                "Синхронизация удалила бы на этом устройстве ${removalsLocal.size} из ${current.size} записей."
            )
        }

        val newRevision = maxOf(remoteDoc?.revision ?: 0, state.revision) + 1
        val pushNeeded = merge.pushNeeded || (remoteDoc == null && merge.merged.isNotEmpty())
        if (pushNeeded) {
            val doc = SyncDocument(revision = newRevision, writtenBy = state.deviceId, writtenAt = now, records = merge.merged.values.toList())
            val bytes = cipher.seal(json.encodeToString(SyncDocument.serializer(), doc).encodeToByteArray())
            transport.upload(bytes, remoteFile?.version)
        }

        // Облако уже обновлено; теперь меняем устройство. Сбой здесь не опасен: состояние не сохранено, и следующий
        // запуск повторит применение.
        val toApply = (merge.toLocal.map { it.id } + deferred.filter { it in merge.merged }).distinct()
            .mapNotNull { merge.merged[it] }
        val applied = applyLocal(toApply, removalsLocal, current)

        val log = state.log + logEntries(merge, now, toApply.size) + removalsLocal.map { SyncLogEntry(now, it, "removed") }
        val next = state.copy(
            revision = if (pushNeeded) newRevision else remoteDoc?.revision ?: state.revision,
            lastSyncAt = now,
            records = merge.merged.values.toList(),
            deferred = applied.deferred.toList(),
            log = log.takeLast(MAX_LOG)
        )
        saveState(json.encodeToString(SyncState.serializer(), next))
        if (applied.changedKinds.isNotEmpty()) onApplied(applied.changedKinds)
        val unchangedInCloud = remoteMap.count { (id, r) -> merge.merged[id]?.sameContent(r) == true }
        return SyncOutcome.Done(
            pulled = toApply.count { it.id !in applied.deferred && byKind.containsKey(it.kind) },
            pushed = if (pushNeeded) merge.merged.size - unchangedInCloud else 0,
            conflicts = merge.conflicts.size,
            deferred = applied.deferred.size,
            failedKinds = applied.failedKinds.toList()
        )
    }

    private fun detectLocal(
        current: Map<String, String>,
        base: Map<String, SyncRecord>,
        deferred: Set<String>,
        device: String,
        now: Long,
        fresh: Boolean
    ): Map<String, SyncRecord> {
        val result = LinkedHashMap<String, SyncRecord>()
        for ((id, data) in current) {
            val b = base[id]
            // Запись ждёт применения (книги не было или сбой): значение на устройстве — не правка пользователя
            if (b != null && id in deferred && !fresh) {
                result[id] = b
                continue
            }
            result[id] = if (!fresh && b != null && !b.deleted && b.data == data) b else SyncRecord(id, now, device, false, data)
        }
        for ((id, b) in base) {
            if (id in result) continue
            val collection = byKind[b.kind]
            result[id] = if (!b.deleted && collection?.tracksDeletion == true && id !in deferred) {
                SyncRecord(id, now, device, deleted = true)
            } else b
        }
        return result
    }

    private class Applied(val deferred: Set<String>, val failedKinds: Set<String>, val changedKinds: Set<String>)

    private fun applyLocal(records: List<SyncRecord>, removals: Set<String>, current: Map<String, String>): Applied {
        val deferred = HashSet<String>()
        val failed = HashSet<String>()
        val changed = HashSet<String>()
        var copied = false
        for (c in collections) {
            val prefix = "${c.kind}/"
            val upserts = records.filter { it.kind == c.kind && !it.deleted && current[it.id] != it.data }
                .associate { it.id.removePrefix(prefix) to it.data }
            val removes = (records.filter { it.kind == c.kind && it.deleted }.map { it.id } + removals.filter { it.startsWith(prefix) })
                .filter { it in current }.map { it.removePrefix(prefix) }.toSet()
            if (upserts.isEmpty() && removes.isEmpty()) continue
            if (!copied) {
                safetyCopy()
                copied = true
            }
            try {
                val pending = c.apply(upserts, removes)
                pending.forEach { deferred += prefix + it }
                if (upserts.size - pending.size > 0 || removes.isNotEmpty()) changed += c.kind
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += c.kind
                upserts.keys.forEach { deferred += prefix + it }
                removes.forEach { deferred += prefix + it }
            }
        }
        return Applied(deferred, failed, changed)
    }

    private fun logEntries(merge: MergeResult, now: Long, pulled: Int): List<SyncLogEntry> =
        merge.conflicts.map {
            SyncLogEntry(now, it.id, "conflict", if (it.winner === it.local) "оставлена правка этого устройства" else "принята правка из облака")
        } + if (pulled > 0) listOf(SyncLogEntry(now, "*", "pulled", pulled.toString())) else emptyList()

    private fun readState(): SyncState =
        loadState()?.let { runCatching { json.decodeFromString(SyncState.serializer(), it) }.getOrNull() } ?: SyncState()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MASS_DELETE_MIN = 5
        const val MAX_LOG = 200
    }
}
