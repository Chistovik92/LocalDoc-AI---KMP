package com.localdoc.ai.tts

import com.localdoc.ai.domain.LanguageVoice
import com.localdoc.ai.domain.VoiceSettings
import com.localdoc.ai.lang.Languages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceSelectorTest {

    private val de = Languages.byCode("de")!!
    private val ru = Languages.byCode("ru")!!
    private val ja = Languages.byCode("ja")!!
    private val piper = PiperSpeechEngine.ENGINE_ID

    private val systemVoices = listOf(
        Voice("ru-RU-x-abc", "Русский", "ru-RU"),
        Voice("de-DE-net", "Deutsch онлайн", "de-DE", offline = false),
        Voice("de-DE-local", "Deutsch", "de-DE"),
        Voice("ja-JP-1", "日本語", "ja-JP")
    )

    @Test
    fun `saved choice for the language wins`() {
        val settings = VoiceSettings(perLanguage = mapOf("de" to LanguageVoice(null, "de-DE-net")))
        val pick = VoiceSelector.pick(de, settings, emptySet(), systemVoices)

        assertEquals("de-DE-net", pick.voiceId)
        assertEquals(VoicePick.Reason.SAVED_CHOICE, pick.reason)
    }

    @Test
    fun `saved choice for a removed piper voice is ignored`() {
        val settings = VoiceSettings(perLanguage = mapOf("de" to LanguageVoice(piper, "vits-piper-de_DE-thorsten-medium")))
        val pick = VoiceSelector.pick(de, settings, emptySet(), systemVoices)
        assertEquals(VoicePick.Reason.SYSTEM_VOICE, pick.reason)
        assertEquals("de-DE-local", pick.voiceId)
    }

    @Test
    fun `current voice is kept when it already speaks the language`() {
        val settings = VoiceSettings(engineId = piper, voiceId = "vits-piper-ru_RU-irina-medium")
        val pick = VoiceSelector.pick(ru, settings, setOf("vits-piper-ru_RU-irina-medium"), systemVoices)

        assertEquals(VoicePick.Reason.CURRENT_VOICE, pick.reason)
        assertEquals(piper, pick.engineId)
    }

    @Test
    fun `installed piper voice is preferred over system voices`() {
        val settings = VoiceSettings(engineId = piper, voiceId = "vits-piper-ru_RU-irina-medium")
        val installed = setOf("vits-piper-ru_RU-irina-medium", "vits-piper-de_DE-thorsten-medium")
        val pick = VoiceSelector.pick(de, settings, installed, systemVoices)

        assertEquals(VoicePick(piper, "vits-piper-de_DE-thorsten-medium", "de-DE", VoicePick.Reason.INSTALLED_PIPER), pick)
    }

    @Test
    fun `offline system voice beats an online one`() {
        val pick = VoiceSelector.pick(de, VoiceSettings(), emptySet(), systemVoices)
        assertEquals("de-DE-local", pick.voiceId)
        assertEquals(null, pick.engineId)
    }

    @Test
    fun `language without any voice falls back to the system engine with the language tag`() {
        val korean = Languages.byCode("ko")!!
        val pick = VoiceSelector.pick(korean, VoiceSettings(engineId = piper, voiceId = "x"), emptySet(), systemVoices)

        assertEquals(VoicePick.Reason.SYSTEM_LANGUAGE_ONLY, pick.reason)
        assertEquals("ko-KR", pick.languageTag)
        assertEquals(null, pick.engineId, "встроенный Piper не умеет этот язык — берём системный синтезатор")
        assertEquals(null, pick.voiceId)
    }

    @Test
    fun `japanese uses the system voice`() {
        assertEquals("ja-JP-1", VoiceSelector.pick(ja, VoiceSettings(), emptySet(), systemVoices).voiceId)
    }

    @Test
    fun `catalog has piper voices for many languages and valid model files`() {
        assertTrue(PiperVoiceCatalog.voices.map { it.language.substringBefore('-') }.toSet().size >= 20)
        assertEquals(PiperVoiceCatalog.voices.size, PiperVoiceCatalog.voices.map { it.id }.toSet().size)
        assertTrue(PiperVoiceCatalog.voices.all { it.modelFile.endsWith(".onnx") && it.id.startsWith("vits-piper-") })
        assertEquals("ru_RU-irina-medium.onnx", PiperVoiceCatalog.byId("vits-piper-ru_RU-irina-medium")?.modelFile)
        assertTrue(PiperVoiceCatalog.forLanguage("de-DE").isNotEmpty())
    }
}
