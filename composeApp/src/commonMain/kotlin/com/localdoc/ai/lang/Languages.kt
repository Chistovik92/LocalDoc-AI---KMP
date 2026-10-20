package com.localdoc.ai.lang

/**
 * Язык для перевода и озвучки. [code] — ISO 639-1, [speechTag] — BCP-47 по умолчанию для голоса,
 * [english] — название для запроса к модели (маленькие модели лучше понимают английское название).
 */
data class Language(val code: String, val name: String, val native: String, val english: String, val speechTag: String)

object Languages {
    val all: List<Language> = listOf(
        Language("ru", "Русский", "Русский", "Russian", "ru-RU"),
        Language("en", "Английский", "English", "English", "en-US"),
        Language("uk", "Украинский", "Українська", "Ukrainian", "uk-UA"),
        Language("be", "Белорусский", "Беларуская", "Belarusian", "be-BY"),
        Language("de", "Немецкий", "Deutsch", "German", "de-DE"),
        Language("fr", "Французский", "Français", "French", "fr-FR"),
        Language("es", "Испанский", "Español", "Spanish", "es-ES"),
        Language("it", "Итальянский", "Italiano", "Italian", "it-IT"),
        Language("pt", "Португальский", "Português", "Portuguese", "pt-BR"),
        Language("pl", "Польский", "Polski", "Polish", "pl-PL"),
        Language("cs", "Чешский", "Čeština", "Czech", "cs-CZ"),
        Language("sk", "Словацкий", "Slovenčina", "Slovak", "sk-SK"),
        Language("bg", "Болгарский", "Български", "Bulgarian", "bg-BG"),
        Language("sr", "Сербский", "Српски", "Serbian", "sr-RS"),
        Language("nl", "Нидерландский", "Nederlands", "Dutch", "nl-NL"),
        Language("sv", "Шведский", "Svenska", "Swedish", "sv-SE"),
        Language("da", "Датский", "Dansk", "Danish", "da-DK"),
        Language("fi", "Финский", "Suomi", "Finnish", "fi-FI"),
        Language("hu", "Венгерский", "Magyar", "Hungarian", "hu-HU"),
        Language("ro", "Румынский", "Română", "Romanian", "ro-RO"),
        Language("el", "Греческий", "Ελληνικά", "Greek", "el-GR"),
        Language("tr", "Турецкий", "Türkçe", "Turkish", "tr-TR"),
        Language("ar", "Арабский", "العربية", "Arabic", "ar-SA"),
        Language("fa", "Персидский", "فارسی", "Persian", "fa-IR"),
        Language("he", "Иврит", "עברית", "Hebrew", "he-IL"),
        Language("hi", "Хинди", "हिन्दी", "Hindi", "hi-IN"),
        Language("th", "Тайский", "ไทย", "Thai", "th-TH"),
        Language("vi", "Вьетнамский", "Tiếng Việt", "Vietnamese", "vi-VN"),
        Language("id", "Индонезийский", "Bahasa Indonesia", "Indonesian", "id-ID"),
        Language("zh", "Китайский", "中文", "Chinese", "zh-CN"),
        Language("ja", "Японский", "日本語", "Japanese", "ja-JP"),
        Language("ko", "Корейский", "한국어", "Korean", "ko-KR"),
        Language("kk", "Казахский", "Қазақша", "Kazakh", "kk-KZ")
    )

    private val byCode = all.associateBy { it.code }

    /** Язык по коду или BCP-47 / локали голоса: «ru», «ru-RU», «en_GB». */
    fun byCode(code: String?): Language? = baseCode(code)?.let(byCode::get)

    /** Основная часть кода: «pt-BR» → «pt». */
    fun baseCode(code: String?): String? =
        code?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() }

    /** Языки совпадают по основной части кода: «pt-BR» == «pt». */
    fun sameLanguage(a: String?, b: String?): Boolean {
        val x = baseCode(a) ?: return false
        return x == baseCode(b)
    }
}

/**
 * Определение языка текста без сети и моделей: по письменности (кириллица, иероглифы, хангыль, арабица…),
 * а внутри латиницы — по частотным служебным словам и характерным буквам. Возвращает null, если
 * текста мало или уверенности нет — тогда решает пользователь.
 */
object LanguageDetector {
    private const val MIN_LETTERS = 12
    private const val SAMPLE_CHARS = 4_000

    fun detect(text: String): String? {
        val sample = text.take(SAMPLE_CHARS)
        val counts = HashMap<Script, Int>()
        var letters = 0
        for (c in sample) {
            val script = scriptOf(c) ?: continue
            counts[script] = (counts[script] ?: 0) + 1
            letters++
        }
        if (letters < MIN_LETTERS) return null
        val top = counts.maxByOrNull { it.value } ?: return null
        val kana = counts[Script.KANA] ?: 0
        return when (top.key) {
            Script.CYRILLIC -> cyrillic(sample)
            Script.LATIN -> latin(sample)
            // Иероглифы вперемешку с кана — японский
            Script.HAN -> if (kana > letters / 20) "ja" else "zh"
            Script.KANA -> "ja"
            Script.HANGUL -> "ko"
            Script.ARABIC -> if (sample.any { it in "پچگژ" }) "fa" else "ar"
            Script.HEBREW -> "he"
            Script.DEVANAGARI -> "hi"
            Script.THAI -> "th"
            Script.GREEK -> "el"
        }
    }

    private enum class Script { CYRILLIC, LATIN, HAN, KANA, HANGUL, ARABIC, HEBREW, DEVANAGARI, THAI, GREEK }

    private fun scriptOf(c: Char): Script? {
        val code = c.code
        return when {
            c in 'a'..'z' || c in 'A'..'Z' || code in 0x00C0..0x024F -> Script.LATIN
            code in 0x0400..0x04FF -> Script.CYRILLIC
            code in 0x0370..0x03FF -> Script.GREEK
            code in 0x4E00..0x9FFF -> Script.HAN
            code in 0x3040..0x30FF -> Script.KANA
            code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> Script.HANGUL
            code in 0x0600..0x06FF -> Script.ARABIC
            code in 0x0590..0x05FF -> Script.HEBREW
            code in 0x0900..0x097F -> Script.DEVANAGARI
            code in 0x0E00..0x0E7F -> Script.THAI
            else -> null
        }
    }

    private fun cyrillic(text: String): String = when {
        text.any { it in "іїєґІЇЄҐ" } && text.none { it in "ыЫэЭъЪ" } -> "uk"
        text.any { it in "ўЎ" } -> "be"
        text.any { it in "әғқңөұүһӘҒҚҢӨҰҮҺ" } -> "kk"
        else -> "ru"
    }

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "of", "to", "in", "is", "that", "it", "was", "for", "with", "as", "his", "he", "she", "they", "you", "this", "have", "not"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "mit", "von", "den", "dem", "sich", "auf", "für", "auch", "er", "sie", "wir"),
        "fr" to setOf("le", "la", "les", "des", "est", "et", "un", "une", "que", "qui", "dans", "pour", "pas", "sur", "il", "elle", "nous", "vous", "au", "ce"),
        "es" to setOf("el", "la", "los", "las", "es", "y", "que", "en", "un", "una", "por", "con", "para", "del", "se", "no", "su", "al", "como", "más"),
        "it" to setOf("il", "lo", "la", "gli", "di", "che", "è", "e", "un", "una", "per", "con", "non", "del", "della", "sono", "si", "da", "come", "più"),
        "pt" to setOf("o", "a", "os", "as", "de", "que", "e", "do", "da", "em", "um", "uma", "para", "com", "não", "se", "por", "mais", "foi", "ele"),
        "pl" to setOf("i", "w", "nie", "na", "z", "się", "to", "że", "do", "jest", "jak", "po", "ale", "tak", "czy", "co", "od", "przez", "oraz", "tym"),
        "nl" to setOf("de", "het", "een", "en", "van", "dat", "is", "niet", "op", "te", "met", "voor", "zijn", "ook", "maar", "aan", "er", "als", "dit", "bij"),
        "tr" to setOf("ve", "bir", "bu", "için", "ile", "de", "da", "çok", "ne", "gibi", "daha", "olarak", "ben", "sen", "var", "değil", "ama", "kadar", "mi", "ki"),
        "cs" to setOf("a", "se", "je", "na", "v", "to", "že", "s", "z", "do", "jsem", "byl", "ale", "pro", "jak", "si", "po", "tak", "co", "nebo"),
        "sv" to setOf("och", "att", "det", "som", "en", "är", "på", "av", "för", "med", "den", "till", "inte", "har", "jag", "han", "var", "om", "ett", "men"),
        "id" to setOf("yang", "dan", "di", "ini", "itu", "dengan", "untuk", "tidak", "dari", "dalam", "akan", "pada", "juga", "saya", "ke", "adalah", "karena", "oleh", "atau", "kami")
    )

    private val WORD_SPLIT = Regex("[^\\p{L}']+")

    private fun latin(text: String): String? {
        val lower = text.lowercase()
        val words = lower.split(WORD_SPLIT).filter { it.isNotEmpty() }
        if (words.size < 4) return null
        val scores = STOPWORDS.mapValues { (_, stop) -> words.count { it in stop } }.toMutableMap()
        // Характерные буквы добавляют очки своему языку
        fun hint(lang: String, chars: String, weight: Int) {
            if (lower.any { it in chars }) scores[lang] = (scores[lang] ?: 0) + words.size / weight + 2
        }
        hint("pl", "ąćęłńóśźż", 10)
        hint("tr", "ğış", 10)
        hint("de", "ßäöü", 12)
        hint("es", "ñ¿¡", 12)
        hint("pt", "ãõ", 12)
        hint("cs", "ěřů", 12)
        val best = scores.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value >= 2 && best.value * 100 >= words.size * 8 }
    }
}
