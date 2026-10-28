package com.localdoc.ai.book

import com.localdoc.ai.tts.SentenceSplitter
import kotlin.coroutines.cancellation.CancellationException

/**
 * Исправление переносов и абзацев с помощью нейросети — для мест, где программные правила ([TextRepair]) не уверены.
 *
 * Сначала применяются программные правила. Затем модели короткими окнами показывают спорные разрывы строк (конец
 * одной строки и начало следующей) и спрашивают, какие из них — один оборванный абзац; а слишком длинные абзацы
 * режутся там, где модель укажет начало нового. Если модель недоступна или ответила не числом, остаются только
 * программные правила: ничего не меняется «наугад».
 */
class TextRepairPlanner(private val model: ChapterModel, private val settings: Settings = Settings()) {

    data class Settings(
        /** Сколько спорных разрывов показывать модели за один вопрос. */
        val windowSize: Int = 6,
        /** Сколько вариантов начала абзаца показывать модели для длинного абзаца. */
        val candidates: Int = 10,
        /** Сколько раз подряд можно делить один длинный абзац. */
        val maxSplitsPerBlock: Int = 4
    )

    /** Программное исправление плюс подсказки модели; [onProgress] получает долю от 0 до 1. */
    suspend fun plan(blocks: List<Block>, chapterStarts: Set<Int> = emptySet(), isPdf: Boolean = false, onProgress: (Float) -> Unit = {}): RepairResult {
        val automatic = TextRepair.repair(blocks, keep = chapterStarts, breakBefore = if (isPdf) chapterStarts else emptySet())
        val fixed = automatic.blocks
        val suspects = TextRepair.suspects(fixed)
        val giants = TextRepair.longParagraphs(fixed)
        val windows = suspects.chunked(settings.windowSize)
        val steps = (windows.size + giants.size).coerceAtLeast(1)
        var done = 0
        fun step() = onProgress(++done / steps.toFloat())

        val joins = HashSet<Int>()
        for (window in windows) {
            joins.addAll(askContinuations(fixed, window))
            step()
        }
        val splits = HashMap<Int, List<Int>>()
        for (index in giants) {
            val cuts = splitGiant(fixed[index].text, 0, ArrayList(), settings.maxSplitsPerBlock)
            if (cuts.isNotEmpty()) splits[index] = cuts
            step()
        }
        onProgress(1f)
        if (joins.isEmpty() && splits.isEmpty()) return automatic
        return automatic.then(TextRepair.edit(fixed, joins, splits))
    }

    /** Номера блоков из [window], после которых строка продолжается в следующем блоке. */
    private suspend fun askContinuations(blocks: List<Block>, window: List<Int>): Set<Int> {
        val list = window.mapIndexed { n, i ->
            "${n + 1}. …${blocks[i].text.trimEnd().takeLast(PREVIEW_CHARS)} ⏎ ${blocks[i + 1].text.trimStart().take(PREVIEW_CHARS)}…"
        }
        val user = "Below are places where a line of a book breaks, shown as the end of one line, ⏎, and the start of the next. " +
            "Which numbered items are one sentence cut in two by mistake (the second part continues the first)? " +
            "Answer with the numbers separated by commas, or 0 if there are none.\n${list.joinToString("\n")}"
        val answer = ask(CONTINUE_SYSTEM, user, CONTINUE_TOKENS) ?: return emptySet()
        return NUMBER.findAll(answer).mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..window.size }.map { window[it - 1] }.toSet()
    }

    /** Места разреза длинного абзаца [text] (смещения от начала целого абзаца [base]); пусто — модель не помогла. */
    private suspend fun splitGiant(text: String, base: Int, found: MutableList<Int>, budget: Int): List<Int> {
        val sentences = SentenceSplitter.ranges(text)
        if (budget <= 0 || text.length <= MAX_PARAGRAPH || sentences.size < MIN_SENTENCES) return found
        val options = thin(sentences.drop(1))
        val list = options.mapIndexed { n, (s, _) -> "${n + 1}. ${text.substring(s).take(PREVIEW_CHARS)}" }
        val user = "Below are the beginnings of sentences from one very long paragraph of a book. Which numbered sentence most " +
            "likely begins a new paragraph?\n${list.joinToString("\n")}\n\nAnswer with the number only."
        val answer = ask(SPLIT_SYSTEM, user, SPLIT_TOKENS) ?: return found
        val number = NUMBER.find(answer)?.value?.toIntOrNull() ?: return found
        val cut = options.getOrNull(number - 1)?.first ?: return found
        found.add(base + cut)
        splitGiant(text.substring(0, cut), base, found, budget - 1)
        splitGiant(text.substring(cut), base + cut, found, budget - 1)
        return found.sorted()
    }

    /** Ответ модели; сбой не срывает работу (кроме отмены): по такому окну остаются программные правила. */
    private suspend fun ask(system: String, user: String, maxTokens: Int): String? =
        try {
            model.complete(system, user, maxTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun <T> thin(all: List<T>): List<T> {
        if (all.size <= settings.candidates) return all
        val step = all.size.toDouble() / settings.candidates
        return List(settings.candidates) { all[(it * step).toInt()] }
    }

    private companion object {
        val NUMBER = Regex("\\d+")
        const val PREVIEW_CHARS = 90
        const val MAX_PARAGRAPH = 1_800
        const val MIN_SENTENCES = 6
        const val CONTINUE_TOKENS = 16
        const val SPLIT_TOKENS = 12
        const val CONTINUE_SYSTEM = "You repair text extracted from books. Reply with numbers only."
        const val SPLIT_SYSTEM = "You split long paragraphs of books. Reply with one number only."
    }
}
