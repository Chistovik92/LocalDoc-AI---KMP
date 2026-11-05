package com.localdoc.ai.rag

/** Пороги отказа от модели эмбеддингов на слабых устройствах. */
object SemanticModelPolicy {
    /** Меньше — модель (≈120 МБ файла, ≈300 МБ в памяти) мешает самому приложению и LLM. */
    const val MIN_TOTAL_MEMORY_MB = 2800L

    /** Медленнее — индексация книги на сотни фрагментов растягивается на минуты. */
    const val MAX_PASSAGE_MILLIS = 1500L

    /** Фрагмент для замера: порядка 280 слов, как рабочие чанки [TextChunker]. */
    val benchmarkPassage: String = (1..280).joinToString(" ") { "слово$it" }

    const val BENCHMARK_RUNS = 3

    /** null — устройство подходит; иначе причина отказа для показа пользователю. */
    fun memoryVerdict(totalMemoryMb: Long): String? =
        if (totalMemoryMb in 1 until MIN_TOTAL_MEMORY_MB)
            "мало памяти: ${totalMemoryMb} МБ, нужно не меньше $MIN_TOTAL_MEMORY_MB МБ"
        else null

    fun speedVerdict(medianMillis: Long): String? =
        if (medianMillis > MAX_PASSAGE_MILLIS)
            "слишком медленно: $medianMillis мс на фрагмент, допустимо до $MAX_PASSAGE_MILLIS мс"
        else null

    fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
}
