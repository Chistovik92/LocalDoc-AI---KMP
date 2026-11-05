package com.localdoc.ai.book

import kotlin.coroutines.cancellation.CancellationException

/** Модель, отвечающая на короткие запросы; в приложении это локальная нейросеть. */
fun interface ChapterModel {
    suspend fun complete(system: String, user: String, maxTokens: Int): String
}

/**
 * Разбиение книги на главы нейросетью, когда явных заголовков нет или глава слишком длинная.
 *
 * Работает окнами: от начала участка отсчитывается минимальный размер главы, затем собираются места, где глава
 * могла бы начаться (после разрыва сцены «* * *», а если разрывов нет — любой абзац окна), и модели показываются
 * только начала этих абзацев. Так запросы короткие и подходят слабому телефону. Если модель ответила не числом
 * или недоступна, берётся первый разрыв сцены либо середина окна: книга всё равно делится.
 */
class ChapterPlanner(private val model: ChapterModel, private val settings: Settings = Settings()) {

    data class Settings(
        /** Не короче: меньше этого размера глава не получится. */
        val minChars: Int = 8_000,
        /** Не длиннее: участок больше этого размера делится дальше. */
        val maxChars: Int = 30_000,
        /** Сколько вариантов начала главы показывать модели за один вопрос. */
        val candidates: Int = 12,
        /** Просить модель назвать каждую новую главу; иначе «Глава N». */
        val titles: Boolean = false
    )

    /**
     * Оглавление с новыми главами внутри длинных участков. Уже найденные главы [existing] остаются как есть;
     * короткие книги и главы возвращаются без изменений. [onProgress] получает долю от 0 до 1.
     */
    suspend fun plan(blocks: List<Block>, existing: List<Chapter> = emptyList(), onProgress: (Float) -> Unit = {}): List<Chapter> {
        val prefix = LongArray(blocks.size + 1)
        for (i in blocks.indices) prefix[i + 1] = prefix[i] + blocks[i].text.length
        val existingStarts = existing.map { it.startBlock }.filter { it in blocks.indices }.toSet()
        val bounds = (existingStarts + 0 + blocks.size).sorted()

        val generated = ArrayList<Int>()
        var reported = 0f
        fun report(value: Float) {
            reported = maxOf(reported, value.coerceIn(0f, 1f))
            onProgress(reported)
        }
        val boundaryShare = if (settings.titles) 0.5f else 1f
        for ((a, b) in bounds.zipWithNext()) {
            var cursor = a
            if (prefix[b] - prefix[a] > settings.maxChars && a !in existingStarts) generated.add(a)
            while (prefix[b] - prefix[cursor] > settings.maxChars) {
                val next = nextStart(blocks, prefix, cursor, b) ?: break
                generated.add(next)
                cursor = next
                report(boundaryShare * next / blocks.size)
            }
        }
        report(boundaryShare)

        val titles = HashMap<Int, String>()
        if (settings.titles) {
            for ((i, start) in generated.withIndex()) {
                nameChapter(blocks, start)?.let { titles[start] = it }
                report(0.5f + 0.5f * (i + 1) / generated.size)
            }
        }
        val created = generated.sorted().mapIndexed { i, start ->
            Chapter(listOfNotNull("Глава ${i + 1}", titles[start]).joinToString(". "), start)
        }
        report(1f)
        return (existing.filter { it.startBlock in blocks.indices } + created).sortedBy { it.startBlock }
    }

    /** Блок, с которого начнётся следующая глава участка [cursor, end); null — делить дальше некуда. */
    private suspend fun nextStart(blocks: List<Block>, prefix: LongArray, cursor: Int, end: Int): Int? {
        var first = cursor + 1
        while (first < end && prefix[first] - prefix[cursor] < settings.minChars) first++
        if (first >= end) return null
        var last = first
        while (last + 1 < end && prefix[last + 1] - prefix[cursor] <= settings.maxChars) last++

        val sceneStarts = (first..last).filter { it > cursor && isSceneBreak(blocks[it - 1]) && !isSceneBreak(blocks[it]) }
        val options = thin(sceneStarts.ifEmpty { (first..last).toList() })
        val fallback = sceneStarts.firstOrNull() ?: options[options.size / 2]
        return askForBoundary(blocks, options) ?: fallback
    }

    private suspend fun askForBoundary(blocks: List<Block>, options: List<Int>): Int? {
        val list = options.mapIndexed { i, block -> "${i + 1}. ${blocks[block].text.take(PREVIEW_CHARS).replace('\n', ' ')}" }
        val user = "Below are the beginnings of paragraphs of a novel. Which numbered paragraph most likely begins a new " +
            "chapter (a change of scene, time, place or point of view)?\n${list.joinToString("\n")}\n\nAnswer with the number only."
        val answer = ask(BOUNDARY_SYSTEM, user, BOUNDARY_TOKENS) ?: return null
        val number = Regex("\\d+").find(answer)?.value?.toIntOrNull() ?: return null
        return options.getOrNull(number - 1)
    }

    private suspend fun nameChapter(blocks: List<Block>, start: Int): String? {
        val excerpt = StringBuilder()
        var i = start
        while (i < blocks.size && excerpt.length < TITLE_EXCERPT_CHARS) excerpt.append(blocks[i++].text).append('\n')
        val user = "Give a short chapter title (2 to 5 words) for the text below, in the language of the text. " +
            "Output only the title.\n\n${excerpt.toString().take(TITLE_EXCERPT_CHARS)}"
        return ask(TITLE_SYSTEM, user, TITLE_TOKENS)?.let(::cleanTitle)
    }

    /** Ответ модели; сбой модели не срывает разбиение (кроме отмены), по такому окну сработает запасной выбор. */
    private suspend fun ask(system: String, user: String, maxTokens: Int): String? =
        try {
            model.complete(system, user, maxTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Не больше [Settings.candidates] вариантов, равномерно по окну. */
    private fun thin(all: List<Int>): List<Int> {
        if (all.size <= settings.candidates) return all
        val step = all.size.toDouble() / settings.candidates
        return List(settings.candidates) { all[(it * step).toInt()] }
    }

    private fun isSceneBreak(block: Block): Boolean {
        val text = block.text.trim()
        return text.isNotEmpty() && text.length <= SCENE_BREAK_MAX && text.none { it.isLetterOrDigit() }
    }

    private fun cleanTitle(raw: String): String? =
        raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.trim('"', '«', '»', '“', '”', '\'', '*', '#', ' ', '.')
            ?.take(TITLE_MAX_CHARS)?.takeIf { it.isNotBlank() }

    private companion object {
        const val PREVIEW_CHARS = 100
        const val SCENE_BREAK_MAX = 12
        const val TITLE_EXCERPT_CHARS = 700
        const val TITLE_MAX_CHARS = 60
        const val BOUNDARY_TOKENS = 12
        const val TITLE_TOKENS = 24
        const val BOUNDARY_SYSTEM = "You help split a novel into chapters. Reply with one number only."
        const val TITLE_SYSTEM = "You name chapters of books. Reply with the title only."
    }
}
