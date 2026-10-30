package com.localdoc.ai

import com.localdoc.ai.i18n.L10n
import kotlin.test.Test
import kotlin.test.assertEquals

/** Тестовый резолвер строк: подменяет getString для JVM-тестов доменной логики. */
object TestStrings {
    private val READER: suspend (org.jetbrains.compose.resources.StringResource, List<Any>) -> String = { resource, args ->
        val name = resource.key.substringAfter(':')
        val text = STRINGS[name] ?: error("нет тестовой строки для $name — добавьте её в TestStrings")
        format(text, args)
    }

    /** Подставляет позиционные аргументы «%1$s»; без аргументов текст возвращается как есть (плейсхолдеры форматирует код). */
    internal fun format(text: String, args: List<Any>): String {
        if (args.isEmpty()) return text
        return Regex("%(\\d+)\\$[sd]").replace(text) { m ->
            args[m.groupValues[1].toInt() - 1].toString()
        }
    }

    /** Устанавливает резолвер; вызывайте в @BeforeTest тестов, чей код использует строки. */
    fun install() {
        L10n.testResolver = READER
    }

    /** Русские значения ключей, используемых не-UI слоями в тестах; синхронно с values/strings.xml. */
    private val STRINGS = mapOf(
        "gen_err_unknown" to "Неизвестная ошибка генерации (%1\$s)",
        "gen_err_busy" to "Модель уже генерирует ответ — дождитесь окончания или нажмите «Стоп»",
        "gen_err_decode" to "Ошибка инференса (не хватает памяти?)",
        "gen_err_invalid_args" to "Некорректные параметры генерации",
        "gen_err_prompt_too_long" to "Запрос не помещается в контекст модели — сократите документ или вопрос",
        "gen_err_tokenize" to "Не удалось токенизировать запрос",
        "novel_no_chapters" to "Не удалось найти главы: у страницы нет оглавления и ссылки «Далее»",
        "novel_searching_next" to "Ищем следующие главы",
        "novel_searching_prev" to "Ищем предыдущие главы",
        "novel_site_stopped" to "Сайт перестал отвечать на главе «%1\$s»: %2\$s. Уже скачанное сохранено — нажмите «Скачать» ещё раз, и загрузка продолжится.",
        "novel_no_response" to "нет ответа",
        "novel_login_required" to "Для скачивания нужен вход на сайт. Войдите и повторите — скачанные главы сохранены.",
        "novel_network_error" to "ошибка сети",
        "novel_chapter_fallback" to "Глава %1\$s",
        "novel_chapter_missing" to "Эту главу не удалось скачать: страница недоступна.",
        "scan_phase_looking" to "Ищем файлы",
        "scan_phase_reading" to "Читаем данные книг",
        "scan_phase_done" to "Готово",
        "scan_found_books" to "Найдено книг: %1\$s (просмотрено файлов: %2\$s)",
        "scan_phase_stopped" to "Остановлено",
        "scan_stopped_message" to "Сканирование остановлено — найденное сохранено",
        "scan_phase_error" to "Ошибка",
        "scan_failed" to "Не удалось просканировать: %1\$s",
        "scan_error_access" to "ошибка доступа",
        "auto_update_notification_title" to "Новые главы",
        "download_missing_chapters" to "Не удалось скачать глав: %1\$s.",
        "download_added_to_library" to "Книга добавлена в библиотеку.",
        "model_not_loaded_inline" to "Сначала загрузите модель в «Настройках».",
        "could_not_open_short" to "не удалось открыть"
    )
}

class TestStringsTest {
    @Test
    fun resolverFormatsPositionalArguments() {
        val text = TestStrings.format("Неизвестная ошибка генерации (%1\$s)", listOf("-42"))
        assertEquals("Неизвестная ошибка генерации (-42)", text)
    }
}
