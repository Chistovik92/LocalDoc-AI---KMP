package com.localdoc.ai.sync

/** Две правки одной записи, сделанные независимо; победила более поздняя (при равенстве — устройство с большим id). */
data class SyncConflict(val id: String, val local: SyncRecord, val remote: SyncRecord, val winner: SyncRecord)

class MergeResult(
    /** Итоговый набор записей. */
    val merged: Map<String, SyncRecord>,
    /** Записи, которые надо применить на этом устройстве. */
    val toLocal: List<SyncRecord>,
    /** true — облако не содержит чего-то из итога и его надо перезаписать. */
    val pushNeeded: Boolean,
    val conflicts: List<SyncConflict>
)

/** Слияние «последняя правка по каждой записи». Чистая функция: удобно проверять тестами. */
object SyncMerge {

    fun wins(a: SyncRecord, b: SyncRecord): Boolean =
        a.updatedAt > b.updatedAt || (a.updatedAt == b.updatedAt && a.device > b.device)

    /**
     * [local] и [remote] — наборы записей (с надгробиями), [base] — общий предок (состояние последней синхронизации).
     * Конфликт — запись, изменённая и здесь, и в облаке относительно [base] по-разному.
     */
    fun merge(
        local: Map<String, SyncRecord>,
        remote: Map<String, SyncRecord>,
        base: Map<String, SyncRecord>,
        now: Long
    ): MergeResult {
        val merged = LinkedHashMap<String, SyncRecord>()
        val toLocal = ArrayList<SyncRecord>()
        val conflicts = ArrayList<SyncConflict>()
        var push = false
        for (id in (local.keys + remote.keys).distinct()) {
            val l = local[id]
            val r = remote[id]
            val winner = when {
                l == null -> r!!
                r == null -> l
                l.sameContent(r) -> if (wins(l, r)) l else r
                else -> {
                    val b = base[id]
                    if (!l.sameContent(b) && !r.sameContent(b)) {
                        conflicts += SyncConflict(id, l, r, if (wins(l, r)) l else r)
                    }
                    if (wins(l, r)) l else r
                }
            }
            if (winner.deleted && now - winner.updatedAt > SyncFormat.TOMBSTONE_TTL_MS) {
                // Надгробие устарело: из итога убираем; если оно ещё лежит в облаке — переписать
                if (r != null) push = true
                continue
            }
            merged[id] = winner
            if (l == null || !l.sameContent(winner)) toLocal += winner
            if (r == null || !r.sameContent(winner)) push = true
        }
        return MergeResult(merged, toLocal, push, conflicts)
    }
}
