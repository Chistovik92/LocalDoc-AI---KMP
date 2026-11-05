package com.localdoc.ai.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCommandParserTest {

    private fun parse(s: String, speaking: String? = null) = VoiceCommandParser.parse(s, speaking)

    @Test
    fun `basic russian commands`() {
        assertEquals(VoiceCommand.NEXT, parse("дальше"))
        assertEquals(VoiceCommand.PREVIOUS, parse("назад"))
        assertEquals(VoiceCommand.PAUSE, parse("пауза"))
        assertEquals(VoiceCommand.PLAY, parse("продолжай"))
    }

    @Test
    fun `case punctuation and yo are ignored`() {
        assertEquals(VoiceCommand.NEXT, parse("Дальше!"))
        assertEquals(VoiceCommand.PAUSE, parse("  ПАУЗА. "))
        assertEquals(VoiceCommand.NEXT, parse("вперёд"))
    }

    @Test
    fun `synonyms and filler words`() {
        assertEquals(VoiceCommand.PAUSE, parse("стоп"))
        assertEquals(VoiceCommand.PAUSE, parse("давай пауза пожалуйста"))
        assertEquals(VoiceCommand.PREVIOUS, parse("повтори"))
        assertEquals(VoiceCommand.NEXT, parse("следующее"))
    }

    @Test
    fun `paragraph commands`() {
        assertEquals(VoiceCommand.NEXT_PARAGRAPH, parse("абзац дальше"))
        assertEquals(VoiceCommand.PREVIOUS_PARAGRAPH, parse("назад абзац"))
    }

    @Test
    fun `english commands`() {
        assertEquals(VoiceCommand.NEXT, parse("next"))
        assertEquals(VoiceCommand.PREVIOUS, parse("back"))
        assertEquals(VoiceCommand.PAUSE, parse("pause"))
    }

    @Test
    fun `ordinary sentences are not commands`() {
        assertNull(parse("он пошёл дальше по дороге и не оглядывался"))
        assertNull(parse("назад к началу истории"))
        assertNull(parse(""))
        assertNull(parse("привет"))
    }

    @Test
    fun `conflicting words give nothing`() {
        assertNull(parse("дальше назад"))
    }

    @Test
    fun `phrase heard from the speaker itself is ignored`() {
        // Микрофон слышит озвучку: «дальше», прозвучавшее в тексте, командой не считается
        assertNull(parse("дальше", speaking = "Он пошёл дальше по дороге."))
        assertEquals(VoiceCommand.NEXT, parse("дальше", speaking = "Совсем другое предложение."))
    }

    @Test
    fun `gate drops repeated commands within the cooldown`() {
        var now = 0L
        val gate = VoiceCommandGate(cooldownMs = 1_500) { now }
        assertEquals(VoiceCommand.NEXT, gate.accept(VoiceCommand.NEXT))
        now += 500
        assertNull(gate.accept(VoiceCommand.NEXT))
        now += 1_500
        assertEquals(VoiceCommand.NEXT, gate.accept(VoiceCommand.NEXT))
    }

    @Test
    fun `gate ignores nothing`() {
        val gate = VoiceCommandGate(cooldownMs = 1_000) { 0L }
        assertNull(gate.accept(null))
    }
}
