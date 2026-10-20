package com.localdoc.ai.domain

import com.localdoc.ai.book.PageLink
import com.localdoc.ai.engine.LlamaEngine
import com.localdoc.ai.model.ChatTemplate
import com.localdoc.ai.parser.ParsedDoc
import com.localdoc.ai.rag.RagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Быстрые действия над открытым документом (One-Click Actions). */
enum class QuickAction(val label: String, val prompt: String) {
    SUMMARY("Краткая выжимка", "Сделай краткую выжимку документа ровно в 5 тезисах. Каждый тезис — один абзац."),
    FACTS("Факты, цифры, даты", "Выдели из текста все ключевые факты, цифры, имена и даты в виде структурированного списка. Не выдумывай ничего."),
    TRANSLATE("Перевод и адаптация", "Переведи и адаптируй текст на простой русский язык, сохранив структуру и смысл."),
    EXPLAIN("Объясни простыми словами", "Объясни содержание документа простыми словами, как для человека без специальных знаний.")
}

/**
 * Гибридный маршрутизатор (Smart Router):
 *  - Режим 1: короткий документ целиком в контексте (быстрый первый токен);
 *  - Режим 2: Map-Reduce — суммаризация длинных документов по блокам;
 *  - Режим 3: локальный RAG — релевантные чанки для вопросов по большим документам.
 */
class SmartPipeline(
    private val engine: LlamaEngine,
    private val rag: RagRepository,
    /** Шаблон диалога загруженной модели (у Qwen, Gemma, Llama, Phi они разные). */
    private val templateProvider: () -> ChatTemplate = { ChatTemplate.CHATML }
) {
    companion object {
        // ~12 000 символов (~3 000 токенов) — порог прямого контекста
        private const val DIRECT_CONTEXT_CHAR_LIMIT = 12_000
        // Размер блока для Map-Reduce (~2 000 токенов)
        private const val MAP_REDUCE_CHAR_LIMIT = 6_000

        val SYSTEM_PROMPT = """
            Ты — точный локальный аналитик документов. Отвечай строго на основе предоставленного текста на русском языке.
            Используй четкую структуру, списки и выделяй ключевые факты, цифры и выводы. Не выдумывай факты, которых нет в источнике.
        """.trimIndent()

        private const val MAX_NAV_LINKS = 120

        /** Убирает блок рассуждений <think>…</think> рассуждающих моделей из видимого ответа. */
        fun stripThinking(text: String): String {
            val open = text.indexOf("<think>")
            if (open < 0) return text
            val close = text.indexOf("</think>", open)
            return if (close < 0) text.substring(0, open).ifBlank { "Модель обдумывает ответ…" }
            else (text.substring(0, open) + text.substring(close + "</think>".length)).trimStart()
        }
    }

    /**
     * Фоллбэк навигации веб-новелл: если эвристики не нашли ссылку, локальная модель
     * выбирает её номер из списка ссылок страницы. Возвращает URL или null.
     */
    suspend fun pickNavigationLink(links: List<PageLink>, target: String): String? {
        val candidates = links.filter { it.text.isNotBlank() }.distinctBy { it.url }.take(MAX_NAV_LINKS)
        if (candidates.isEmpty()) return null
        val list = candidates.mapIndexed { i, l -> "${i + 1}. ${l.text.take(60)} — ${l.url.takeLast(80)}" }.joinToString("\n")
        val prompt = templateProvider().build(
            "Ты помогаешь читалке ориентироваться на сайте веб-новеллы. Отвечай только числом.",
            "Ссылки страницы:\n$list\n\nКакой номер у ссылки на $target? Ответь одним числом. Если такой ссылки нет — ответь 0."
        )
        val answer = StringBuilder()
        engine.generateStream(prompt, 12).collect { answer.append(it) }
        val number = Regex("\\d+").find(stripThinking(answer.toString()))?.value?.toIntOrNull() ?: return null
        return candidates.getOrNull(number - 1)?.url
    }

    /** Простой чат без документа. */
    fun chat(message: String, maxTokens: Int = 1536): Flow<String> =
        engine.generateStream(buildPrompt(SYSTEM_PROMPT, message), maxTokens)

    /** Запрос по документу с автоматическим выбором режима. */
    fun ask(doc: ParsedDoc, question: String, maxTokens: Int = 1536): Flow<String> = flow {
        if (doc.content.length <= DIRECT_CONTEXT_CHAR_LIMIT) {
            // Режим 1: документ целиком
            val userMessage = buildString {
                appendLine("КОНТЕКСТ ДОКУМЕНТА («${doc.title}»):")
                appendLine(doc.content)
                appendLine()
                append("ЗАДАНИЕ / ВОПРОС: $question")
            }
            emitAll(engine.generateStream(buildPrompt(SYSTEM_PROMPT, userMessage), maxTokens))
        } else {
            // Режим 3: локальный RAG. Индекс строится один раз на документ:
            // ключ включает хэш содержимого, чтобы разные документы с одним заголовком не смешивались
            val docId = "${doc.title}#${doc.content.hashCode()}"
            rag.ensureIndexed(docId = docId, rawText = doc.content)
            val chunks = rag.retrieveTopK(docId = docId, question = question, topK = 4)
            val contextBlock = if (chunks.isEmpty()) {
                doc.content.take(DIRECT_CONTEXT_CHAR_LIMIT)
            } else {
                chunks.joinToString("\n\n---\n\n") { "[Фрагмент #${it.section}]: ${it.text}" }
            }
            val userMessage = buildString {
                appendLine("ФРАГМЕНТЫ ДОКУМЕНТА («${doc.title}»), НАИБОЛЕЕ РЕЛЕВАНТНЫЕ ВОПРОСУ:")
                appendLine(contextBlock)
                appendLine()
                append("ЗАДАНИЕ / ВОПРОС: $question")
            }
            emitAll(engine.generateStream(buildPrompt(SYSTEM_PROMPT, userMessage), maxTokens))
        }
    }.flowOn(Dispatchers.Default) // индексация и поиск по чанкам — не на главном потоке

    /** Режим 2: Map-Reduce — суммаризация длинных документов по блокам. */
    fun summarizeLongDoc(doc: ParsedDoc): Flow<String> = flow {
        val blocks = doc.content.chunked(MAP_REDUCE_CHAR_LIMIT)
        val partials = StringBuilder()
        for ((idx, block) in blocks.withIndex()) {
            val mapPrompt = buildPrompt(
                SYSTEM_PROMPT,
                "БЛОК ${idx + 1} из ${blocks.size} документа «${doc.title}»:\n$block\n\n" +
                    "Задание: сделай сжатую выжимку этого блока (3-5 пунктов)."
            )
            val collected = StringBuilder()
            engine.generateStream(mapPrompt, 400).collect { token ->
                collected.append(token)
                emit(token)
            }
            partials.appendLine("Блок ${idx + 1}: ${collected.trim()}")
        }
        val reducePrompt = buildPrompt(
            SYSTEM_PROMPT,
            "Ниже собраны выжимки всех блоков документа «${doc.title}».\n${partials}\n" +
                "Задание: объедини их в цельное финальное резюме документа на русском языке."
        )
        emitAll(engine.generateStream(reducePrompt, 1024))
    }

    private fun buildPrompt(system: String, user: String): String = templateProvider().build(system, user)
}
