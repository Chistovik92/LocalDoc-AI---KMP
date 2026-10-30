package com.localdoc.ai.domain

import com.localdoc.ai.i18n.L10n
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.engine.LlamaEngine
import com.localdoc.ai.lang.Language
import com.localdoc.ai.model.ChatTemplate
import com.localdoc.ai.parser.ParsedDoc
import com.localdoc.ai.rag.RagRepository
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.ai_nav_system
import com.localdoc.ai.resources.ai_nav_user
import com.localdoc.ai.resources.ai_prompt_context_header
import com.localdoc.ai.resources.ai_prompt_fragment_item
import com.localdoc.ai.resources.ai_prompt_fragments_header
import com.localdoc.ai.resources.ai_prompt_map_block
import com.localdoc.ai.resources.ai_prompt_map_partial
import com.localdoc.ai.resources.ai_prompt_map_task
import com.localdoc.ai.resources.ai_prompt_question
import com.localdoc.ai.resources.ai_prompt_reduce_header
import com.localdoc.ai.resources.ai_prompt_reduce_task
import com.localdoc.ai.resources.ai_prompt_system
import com.localdoc.ai.resources.quick_action_explain
import com.localdoc.ai.resources.quick_action_explain_prompt
import com.localdoc.ai.resources.quick_action_facts
import com.localdoc.ai.resources.quick_action_facts_prompt
import com.localdoc.ai.resources.quick_action_summary
import com.localdoc.ai.resources.quick_action_summary_prompt
import com.localdoc.ai.resources.quick_action_translate
import com.localdoc.ai.resources.quick_action_translate_prompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.compose.resources.StringResource

/** Быстрые действия над открытым документом (One-Click Actions); подписи и промпты — в ресурсах, на языке интерфейса. */
enum class QuickAction(val labelRes: StringResource, val promptRes: StringResource) {
    SUMMARY(Res.string.quick_action_summary, Res.string.quick_action_summary_prompt),
    FACTS(Res.string.quick_action_facts, Res.string.quick_action_facts_prompt),
    TRANSLATE(Res.string.quick_action_translate, Res.string.quick_action_translate_prompt),
    EXPLAIN(Res.string.quick_action_explain, Res.string.quick_action_explain_prompt);

    suspend fun label(): String = L10n.get(labelRes)
    suspend fun prompt(): String = L10n.get(promptRes)
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

        /** Локализованный системный промпт ассистента — на языке интерфейса. */
        suspend fun systemPrompt(): String = L10n.get(Res.string.ai_prompt_system)

        private const val MAX_NAV_LINKS = 120

        /** Убирает блок рассуждений <think>…</think> рассуждающих моделей из видимого ответа. */
        fun stripThinking(text: String): String {
            val open = text.indexOf("<think>")
            if (open < 0) return text
            val close = text.indexOf("</think>", open)
            // Пустой текст не подменяем служебной строкой: UI показывает свою подпись «думает…»
            return if (close < 0) text.substring(0, open)
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
            L10n.get(Res.string.ai_nav_system),
            L10n.get(Res.string.ai_nav_user, list, target)
        )
        val answer = StringBuilder()
        engine.generateStream(prompt, 12).collect { answer.append(it) }
        val number = Regex("\\d+").find(stripThinking(answer.toString()))?.value?.toIntOrNull() ?: return null
        return candidates.getOrNull(number - 1)?.url
    }

    /**
     * Перевод фрагмента на язык [target] (с [source], если он известен; иначе модель определяет его сама).
     * Запрос на английском: маленькие локальные модели точнее следуют таким инструкциям.
     */
    fun translate(text: String, target: Language, source: Language? = null, maxTokens: Int = 1536): Flow<String> {
        val from = source?.let { " from ${it.english}" }.orEmpty()
        val system = "You are a professional literary translator. Translate faithfully, keep the meaning, tone and " +
            "names. Output only the translation: no comments, no quotes, no original text."
        val user = "Translate the following text$from to ${target.english}.\n\n$text"
        return engine.generateStream(buildPrompt(system, user), maxTokens)
    }

    /**
     * Словарная статья для слова или выражения [word] в предложении [sentence]: перевод на язык [target]
     * и короткое определение. Предложение даёт контекст — от него зависит выбор значения.
     */
    fun lookup(word: String, sentence: String, target: Language, source: Language? = null): Flow<String> {
        val from = source?.let { " The text is in ${it.english}." }.orEmpty()
        val system = "You are a concise bilingual dictionary. Answer with one line in the format " +
            "\"<translation> — <short definition>\", both in ${target.english}. No quotes, no extra comments."
        val user = "Word or phrase: \"$word\".$from\nIt is used in this sentence: \"$sentence\"\n" +
            "Give its meaning in this context, translated into ${target.english}."
        return engine.generateStream(buildPrompt(system, user), maxTokens = 160)
    }

    /** Короткий ответ модели на разовый запрос — для служебных задач вроде разбиения книги на главы. */
    suspend fun complete(system: String, user: String, maxTokens: Int): String {
        val answer = StringBuilder()
        engine.generateStream(buildPrompt(system, user), maxTokens).collect { answer.append(it) }
        return stripThinking(answer.toString()).trim()
    }

    /** Простой чат без документа. */
    fun chat(message: String, maxTokens: Int = 1536): Flow<String> = flow {
        emitAll(engine.generateStream(buildPrompt(systemPrompt(), message), maxTokens))
    }.flowOn(Dispatchers.Default)

    /** Запрос по документу с автоматическим выбором режима. */
    fun ask(doc: ParsedDoc, question: String, maxTokens: Int = 1536): Flow<String> = flow {
        if (doc.content.length <= DIRECT_CONTEXT_CHAR_LIMIT) {
            // Режим 1: документ целиком
            val userMessage = buildString {
                appendLine(L10n.get(Res.string.ai_prompt_context_header, doc.title))
                appendLine(doc.content)
                appendLine()
                appendLine(L10n.get(Res.string.ai_prompt_question, question))
            }
            emitAll(engine.generateStream(buildPrompt(systemPrompt(), userMessage), maxTokens))
        } else {
            // Режим 3: локальный RAG. Индекс строится один раз на документ:
            // ключ включает хэш содержимого, чтобы разные документы с одним заголовком не смешивались
            val docId = "${doc.title}#${doc.content.hashCode()}"
            rag.ensureIndexed(docId = docId, rawText = doc.content)
            val chunks = rag.retrieveTopK(docId = docId, question = question, topK = 4, rawText = doc.content)
            val contextBlock = if (chunks.isEmpty()) {
                doc.content.take(DIRECT_CONTEXT_CHAR_LIMIT)
            } else {
                val template = L10n.get(Res.string.ai_prompt_fragment_item, "%s", "%s")
                chunks.joinToString("\n\n---\n\n") { template.format(it.section, it.text) }
            }
            val userMessage = buildString {
                appendLine(L10n.get(Res.string.ai_prompt_fragments_header, doc.title))
                appendLine(contextBlock)
                appendLine()
                appendLine(L10n.get(Res.string.ai_prompt_question, question))
            }
            emitAll(engine.generateStream(buildPrompt(systemPrompt(), userMessage), maxTokens))
        }
    }.flowOn(Dispatchers.Default) // индексация и поиск по чанкам — не на главном потоке

    /** Режим 2: Map-Reduce — суммаризация длинных документов по блокам. */
    fun summarizeLongDoc(doc: ParsedDoc): Flow<String> = flow {
        val blocks = doc.content.chunked(MAP_REDUCE_CHAR_LIMIT)
        val partials = StringBuilder()
        for ((idx, block) in blocks.withIndex()) {
            val mapPrompt = buildPrompt(
                systemPrompt(),
                L10n.get(Res.string.ai_prompt_map_block, idx + 1, blocks.size, doc.title) + "\n$block\n\n" +
                    L10n.get(Res.string.ai_prompt_map_task)
            )
            val collected = StringBuilder()
            engine.generateStream(mapPrompt, 400).collect { token ->
                collected.append(token)
                emit(token)
            }
            partials.appendLine(L10n.get(Res.string.ai_prompt_map_partial, idx + 1, collected.trim()))
        }
        val reducePrompt = buildPrompt(
            systemPrompt(),
            L10n.get(Res.string.ai_prompt_reduce_header, doc.title) + "\n${partials}\n" +
                L10n.get(Res.string.ai_prompt_reduce_task)
        )
        emitAll(engine.generateStream(reducePrompt, 1024))
    }.flowOn(Dispatchers.Default)

    private fun buildPrompt(system: String, user: String): String = templateProvider().build(system, user)
}
