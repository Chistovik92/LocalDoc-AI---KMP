package com.localdoc.ai.tts

import com.localdoc.ai.domain.VoiceSettings
import com.localdoc.ai.lang.Language
import com.localdoc.ai.lang.Languages

/**
 * Выбранный голос для языка. [engineId] null — системный синтезатор, [PiperSpeechEngine.ENGINE_ID] — встроенный Piper.
 * [languageTag] нужен системному синтезатору, когда конкретный голос не найден.
 */
data class VoicePick(val engineId: String?, val voiceId: String?, val languageTag: String, val reason: Reason) {
    enum class Reason { SAVED_CHOICE, CURRENT_VOICE, INSTALLED_PIPER, SYSTEM_VOICE, SYSTEM_LANGUAGE_ONLY }
}

/**
 * Подбор голоса под язык текста или перевода. Порядок: выбор пользователя для этого языка →
 * текущий голос, если он уже говорит на этом языке → установленный голос Piper → системный голос
 * языка (офлайн лучше) → системный синтезатор с языком по умолчанию.
 */
object VoiceSelector {

    fun pick(
        language: Language,
        settings: VoiceSettings,
        installedPiper: Set<String>,
        systemVoices: List<Voice>
    ): VoicePick {
        val tag = language.speechTag

        settings.perLanguage[language.code]?.let { saved ->
            if (isUsable(saved.engineId, saved.voiceId, installedPiper, systemVoices)) {
                return VoicePick(saved.engineId, saved.voiceId, tag, VoicePick.Reason.SAVED_CHOICE)
            }
        }

        if (isUsable(settings.engineId, settings.voiceId, installedPiper, systemVoices) &&
            voiceLanguage(settings.engineId, settings.voiceId, systemVoices)?.let { Languages.sameLanguage(it, language.code) } == true
        ) {
            return VoicePick(settings.engineId, settings.voiceId, tag, VoicePick.Reason.CURRENT_VOICE)
        }

        PiperVoiceCatalog.forLanguage(language.code).firstOrNull { it.id in installedPiper }?.let {
            return VoicePick(PiperSpeechEngine.ENGINE_ID, it.id, tag, VoicePick.Reason.INSTALLED_PIPER)
        }

        systemVoices.filter { Languages.sameLanguage(it.language, language.code) }
            .sortedWith(compareBy({ !it.offline }, { !it.language.equals(tag, ignoreCase = true) }, { it.name }))
            .firstOrNull()
            ?.let { return VoicePick(systemEngineOf(settings), it.id, tag, VoicePick.Reason.SYSTEM_VOICE) }

        return VoicePick(systemEngineOf(settings), null, tag, VoicePick.Reason.SYSTEM_LANGUAGE_ONLY)
    }

    /** Системный движок выбранного пользователем набора; при встроенном голосе — движок по умолчанию. */
    private fun systemEngineOf(settings: VoiceSettings): String? =
        settings.engineId?.takeIf { it != PiperSpeechEngine.ENGINE_ID }

    private fun isUsable(engineId: String?, voiceId: String?, installedPiper: Set<String>, systemVoices: List<Voice>): Boolean = when {
        engineId == PiperSpeechEngine.ENGINE_ID -> voiceId != null && voiceId in installedPiper
        voiceId == null -> false
        else -> systemVoices.any { it.id == voiceId }
    }

    private fun voiceLanguage(engineId: String?, voiceId: String?, systemVoices: List<Voice>): String? =
        if (engineId == PiperSpeechEngine.ENGINE_ID) PiperVoiceCatalog.byId(voiceId)?.language
        else systemVoices.firstOrNull { it.id == voiceId }?.language
}
