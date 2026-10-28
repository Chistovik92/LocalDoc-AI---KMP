package com.localdoc.ai.net

import kotlinx.serialization.Serializable

/**
 * «Скорость скачивания» новелл: пауза между запросами к одному сайту и длинный отдых через случайное число запросов.
 * По умолчанию — [CAUTIOUS]; быстрее сайты нагружаются только по явному выбору пользователя и никогда
 * чаще, чем раз в [MIN_GAP_MS] (с учётом разброса пауз).
 */
@Serializable
enum class DownloadSpeed(
    val label: String,
    val hint: String,
    val intervalMs: Long,
    val restAfterMin: Int,
    val restAfterMax: Int,
    val restMs: Long
) {
    CAUTIOUS("Осторожно", "Около 10 секунд между главами и минута отдыха — сайты не принимают скачивание за атаку", 10_000L, 12, 31, 60_000L),
    NORMAL("Обычно", "Около 4 секунд между главами, отдых реже", 4_000L, 25, 60, 30_000L),
    FAST("Быстро", "Около 2 секунд между главами; сайт может ограничить доступ — включайте для своих серверов", 1_800L, 50, 120, 15_000L);

    /** Сколько запросов пройдёт до следующего длинного отдыха; [random] — число от 0 (включая) до 1 (не включая). */
    fun requestsUntilRest(random: Double): Int =
        (restAfterMin + (random * (restAfterMax - restAfterMin + 1)).toInt()).coerceIn(restAfterMin, restAfterMax)

    companion object {
        /** Быстрее прежних 1,2 секунды между запросами не бывает ни в одном режиме, даже с самым малым разбросом. */
        const val MIN_GAP_MS = 1_200L

        /** Разброс пауз: ±30 %. */
        const val JITTER = 0.3
    }
}
