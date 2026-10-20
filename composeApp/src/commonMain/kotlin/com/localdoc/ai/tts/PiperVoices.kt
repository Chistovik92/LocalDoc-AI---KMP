package com.localdoc.ai.tts

import com.localdoc.ai.download.Downloadable

/**
 * Открытый голос Piper (VITS) в сборке sherpa-onnx: архив содержит модель .onnx,
 * tokens.txt и espeak-ng-data — всё, что нужно для офлайн-синтеза.
 */
data class PiperVoice(
    /** Имя архива без расширения: vits-piper-ru_RU-irina-medium. */
    override val id: String,
    val name: String,
    /** BCP-47: ru-RU, en-US, en-GB. */
    val language: String,
    val male: Boolean,
    val description: String,
    override val sizeBytes: Long
) : Downloadable {
    /** Файл модели внутри архива: ru_RU-irina-medium.onnx. */
    val modelFile: String get() = id.removePrefix(ID_PREFIX) + ".onnx"

    override val url: String get() = "$RELEASES/$id.tar.bz2"

    val sizeMb: Long get() = sizeBytes / (1024L * 1024L)

    companion object {
        private const val ID_PREFIX = "vits-piper-"
        // Официальные сборки голосов Piper для sherpa-onnx (лицензии голосов — в MODEL_CARD архива)
        private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    }
}

/** Каталог встроенных голосов: русские ru_RU и несколько английских. */
object PiperVoiceCatalog {
    val voices: List<PiperVoice> = listOf(
        PiperVoice("vits-piper-ru_RU-irina-medium", "Ирина", "ru-RU", male = false, "Женский, спокойный — хорош для книг", 67_153_308),
        PiperVoice("vits-piper-ru_RU-denis-medium", "Денис", "ru-RU", male = true, "Мужской, ровный", 67_190_991),
        PiperVoice("vits-piper-ru_RU-dmitri-medium", "Дмитрий", "ru-RU", male = true, "Мужской, низкий", 67_188_551),
        PiperVoice("vits-piper-ru_RU-ruslan-medium", "Руслан", "ru-RU", male = true, "Мужской, выразительный", 67_210_684),
        PiperVoice("vits-piper-en_US-lessac-medium", "Lessac", "en-US", male = false, "English (US), female", 67_230_653),
        PiperVoice("vits-piper-en_US-amy-medium", "Amy", "en-US", male = false, "English (US), female", 67_223_746),
        PiperVoice("vits-piper-en_US-ryan-medium", "Ryan", "en-US", male = true, "English (US), male", 67_213_100),
        PiperVoice("vits-piper-en_GB-alan-medium", "Alan", "en-GB", male = true, "English (UK), male", 67_220_121),
        PiperVoice("vits-piper-en_GB-jenny_dioco-medium", "Jenny", "en-GB", male = false, "English (UK), female", 67_225_842),
        PiperVoice("vits-piper-de_DE-thorsten-medium", "Thorsten", "de-DE", male = true, "Deutsch, männlich", 67_214_254),
        PiperVoice("vits-piper-fr_FR-siwis-medium", "Siwis", "fr-FR", male = false, "Français, féminin", 67_207_459),
        PiperVoice("vits-piper-es_ES-davefx-medium", "DaveFX", "es-ES", male = true, "Español, masculino", 67_184_952),
        PiperVoice("vits-piper-it_IT-paola-medium", "Paola", "it-IT", male = false, "Italiano, femminile", 67_221_173),
        PiperVoice("vits-piper-pt_BR-faber-medium", "Faber", "pt-BR", male = true, "Português (Brasil), masculino", 67_183_065),
        PiperVoice("vits-piper-pl_PL-gosia-medium", "Gosia", "pl-PL", male = false, "Polski, żeński", 67_211_182),
        PiperVoice("vits-piper-uk_UA-ukrainian_tts-medium", "Українська", "uk-UA", male = false, "Українська, жіночий", 80_355_083),
        PiperVoice("vits-piper-cs_CZ-jirka-medium", "Jirka", "cs-CZ", male = true, "Čeština, mužský", 67_212_775),
        PiperVoice("vits-piper-sk_SK-lili-medium", "Lili", "sk-SK", male = false, "Slovenčina, ženský", 67_183_092),
        PiperVoice("vits-piper-sr_RS-serbski_institut-medium", "Serbski", "sr-RS", male = false, "Српски", 80_298_557),
        PiperVoice("vits-piper-nl_NL-pim-medium", "Pim", "nl-NL", male = true, "Nederlands, mannelijk", 67_240_538),
        PiperVoice("vits-piper-sv_SE-lisa-medium", "Lisa", "sv-SE", male = false, "Svenska, kvinnlig", 67_223_955),
        PiperVoice("vits-piper-da_DK-talesyntese-medium", "Talesyntese", "da-DK", male = false, "Dansk", 67_185_257),
        PiperVoice("vits-piper-fi_FI-harri-medium", "Harri", "fi-FI", male = true, "Suomi, mies", 67_205_198),
        PiperVoice("vits-piper-hu_HU-anna-medium", "Anna", "hu-HU", male = false, "Magyar, női", 67_167_701),
        PiperVoice("vits-piper-ro_RO-mihai-medium", "Mihai", "ro-RO", male = true, "Română, masculin", 67_182_137),
        PiperVoice("vits-piper-el_GR-rapunzelina-low", "Rapunzelina", "el-GR", male = false, "Ελληνικά, γυναικεία", 67_105_521),
        PiperVoice("vits-piper-tr_TR-dfki-medium", "DFKI", "tr-TR", male = false, "Türkçe, kadın", 67_201_221),
        PiperVoice("vits-piper-ar_JO-kareem-medium", "Kareem", "ar-JO", male = true, "العربية، ذكر", 67_177_830),
        PiperVoice("vits-piper-fa_IR-ganji-medium", "Ganji", "fa-IR", male = true, "فارسی", 67_186_157),
        PiperVoice("vits-piper-hi_IN-pratham-medium", "Pratham", "hi-IN", male = true, "हिन्दी, पुरुष", 67_238_438),
        PiperVoice("vits-piper-id_ID-news_tts-medium", "News TTS", "id-ID", male = false, "Bahasa Indonesia", 67_243_848),
        PiperVoice("vits-piper-vi_VN-vais1000-medium", "VAIS1000", "vi-VN", male = false, "Tiếng Việt", 67_154_040),
        PiperVoice("vits-piper-kk_KZ-iseke-x_low", "Iseke", "kk-KZ", male = true, "Қазақша, компактный", 26_517_675),
        PiperVoice("vits-piper-zh_CN-huayan-medium", "Huayan", "zh-CN", male = false, "中文，女声", 67_255_926)
    )

    fun byId(id: String?): PiperVoice? = voices.firstOrNull { it.id == id }

    /** Голоса Piper на языке [code] («de», «de-DE»): порядок каталога. */
    fun forLanguage(code: String?): List<PiperVoice> {
        val base = code?.lowercase()?.substringBefore('-') ?: return emptyList()
        return voices.filter { it.language.lowercase().substringBefore('-') == base }
    }
}

/** Пути к файлам установленного голоса. */
data class PiperVoicePaths(val model: String, val tokens: String, val dataDir: String)
