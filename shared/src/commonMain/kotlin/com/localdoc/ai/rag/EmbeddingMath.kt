package com.localdoc.ai.rag

import kotlin.math.sqrt

/** Пулинг и нормализация выхода модели эмбеддингов. */
object EmbeddingMath {

    /**
     * Среднее по токенам с `attentionMask == 1`. [hidden] — плоский тензор `[токены × dim]`
     * (last_hidden_state без размерности батча).
     */
    fun meanPool(hidden: FloatArray, attentionMask: LongArray, dim: Int): FloatArray {
        val sum = FloatArray(dim)
        var count = 0
        for (t in attentionMask.indices) {
            if (attentionMask[t] == 0L) continue
            val offset = t * dim
            for (d in 0 until dim) sum[d] += hidden[offset + d]
            count++
        }
        if (count > 0) for (d in 0 until dim) sum[d] /= count
        return sum
    }

    /** L2-нормализация в новый массив; нулевой вектор остаётся нулевым. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm < 1e-12f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }
}
