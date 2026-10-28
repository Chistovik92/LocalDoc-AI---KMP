package com.localdoc.ai.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки локализации интерфейса. Лежат в androidUnitTest, а не в commonTest: читают исходники
 * и словари с диска (рабочий каталог тестов — каталог модуля composeApp).
 */
class LocalizationTest {
    private val cyrillic = Regex("[А-Яа-яЁё]")
    private val placeholder = Regex("%\\d+\\$[sd]")
    private val languages = listOf("ru", "en", "uk", "de", "es")

    private val uiSources = File("src/commonMain/kotlin/com/localdoc/ai/ui")
    private val resources = File("src/commonMain/composeResources")

    /**
     * Исключения: кириллица в литерале допустима только здесь. Ключ — «файл: литерал».
     * Это пробная фраза, которую произносит голос (язык фразы = язык голоса), образец шрифта
     * и названия языков на родном языке (автонимы), которые не переводятся.
     */
    private val allowedLiterals = setOf(
        "BuiltinVoices.kt: \"Так звучит выбранный голос.\"",
        "ReaderSheets.kt: \"Так звучит выбранный голос.\"",
        "ReaderSheets.kt: \"Аа\"",
        "AppLanguage.kt: \"Русский\"",
        "AppLanguage.kt: \"Українська\""
    )

    /** Строковые литералы Kotlin-кода без комментариев и символьных литералов. */
    private fun literals(src: String): List<String> {
        val out = mutableListOf<String>()
        val quote = '"'
        val backslash = '\\'
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                src[i] == '\'' -> {
                    i++
                    if (src[i] == backslash) i++
                    i += 2
                }
                src[i] == quote -> {
                    val start = i
                    i++
                    var depth = 0
                    while (i < src.length) {
                        val c = src[i]
                        if (c == backslash) { i += 2; continue }
                        if (depth == 0 && c == quote) break
                        if (c == '$' && src.startsWith("{", i + 1)) { depth++; i += 2; continue }
                        if (depth > 0 && c == '{') depth++
                        if (depth > 0 && c == '}') depth--
                        i++
                    }
                    i++
                    out += src.substring(start, minOf(i, src.length))
                }
                else -> i++
            }
        }
        return out
    }

    @Test
    fun `в исходниках экранов нет кириллических строк-литералов вне ресурсов`() {
        assertTrue(uiSources.isDirectory, "не найден ${uiSources.absolutePath}")
        val offenders = uiSources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            literals(file.readText()).filter { cyrillic.containsMatchIn(it) }
                .map { "${file.name}: $it" }.filter { it !in allowedLiterals }
        }.toList()
        assertTrue(offenders.isEmpty(), "Строки интерфейса нужно вынести в composeResources/values*/strings.xml:\n" + offenders.joinToString("\n"))
    }

    private class Dictionary(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(lang: String): Dictionary {
        val dir = if (lang == "ru") "values" else "values-$lang"
        val xml = File(resources, "$dir/strings.xml").readText()
        val strings = Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals = Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { m ->
            m.groupValues[1] to Regex("<item quantity=\"(\\w+)\">(.*?)</item>").findAll(m.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
        return Dictionary(strings, plurals)
    }

    private val dictionaries by lazy { languages.associateWith(::load) }

    @Test
    fun `каждый ключ есть во всех языках`() {
        val base = dictionaries.getValue("ru")
        assertTrue(base.strings.isNotEmpty())
        for ((lang, dict) in dictionaries) {
            assertEquals(base.strings.keys, dict.strings.keys, "строки $lang расходятся с ru")
            assertEquals(base.plurals.keys, dict.plurals.keys, "plurals $lang расходятся с ru")
        }
    }

    @Test
    fun `плейсхолдеры совпадают во всех языках`() {
        val base = dictionaries.getValue("ru")
        fun holders(text: String) = placeholder.findAll(text).map { it.value.replace('d', 's') }.toSortedSet()
        for ((lang, dict) in dictionaries) {
            for ((key, ru) in base.strings) {
                assertEquals(holders(ru), holders(dict.strings.getValue(key)), "плейсхолдеры «$key» в $lang")
            }
        }
    }

    @Test
    fun `plurals содержат нужные формы языка`() {
        val required = mapOf(
            "ru" to setOf("one", "few", "many", "other"), "uk" to setOf("one", "few", "many", "other"),
            "en" to setOf("one", "other"), "de" to setOf("one", "other"), "es" to setOf("one", "many", "other")
        )
        for ((lang, dict) in dictionaries) {
            for ((name, forms) in dict.plurals) {
                assertEquals(required.getValue(lang), forms.keys, "формы «$name» в $lang")
            }
        }
    }

    @Test
    fun `переводы не пустые и без кириллицы в латинских языках`() {
        val bareApostrophe = Regex("(?<!\\\\)'")
        for ((lang, dict) in dictionaries) {
            for ((key, text) in dict.strings) {
                assertTrue(text.isNotBlank(), "пустая строка «$key» в $lang")
                assertEquals(text.trim(), text, "пробелы по краям «$key» в $lang (XML их не сохраняет)")
                assertTrue(!bareApostrophe.containsMatchIn(text), "неэкранированный апостроф «$key» в $lang")
                if (lang in setOf("en", "de", "es")) {
                    assertTrue(!cyrillic.containsMatchIn(text), "кириллица в «$key» ($lang)")
                }
            }
        }
    }

    @Test
    fun `ключи используются в коде`() {
        val code = File("src/commonMain/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val unused = dictionaries.getValue("ru").strings.keys.filter { !code.contains("string.$it") }
        assertTrue(unused.isEmpty(), "ключи без использования:\n" + unused.joinToString("\n"))
    }
}
