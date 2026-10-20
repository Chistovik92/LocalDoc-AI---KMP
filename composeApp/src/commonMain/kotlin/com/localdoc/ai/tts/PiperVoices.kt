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
        PiperVoice("vits-piper-en_GB-jenny_dioco-medium", "Jenny", "en-GB", male = false, "English (UK), female", 67_225_842)
    )

    fun byId(id: String?): PiperVoice? = voices.firstOrNull { it.id == id }
}

/** Пути к файлам установленного голоса. */
data class PiperVoicePaths(val model: String, val tokens: String, val dataDir: String)
