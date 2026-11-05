package com.localdoc.ai.voice

import com.localdoc.ai.net.nowMillis
import com.localdoc.ai.tts.ReadAloudController

/** Команда голосового управления чтением. */
enum class VoiceCommand { NEXT, PREVIOUS, NEXT_PARAGRAPH, PREVIOUS_PARAGRAPH, PAUSE, PLAY }

/**
 * Разбор фразы, которую вернул распознаватель. Команда — только короткая фраза целиком из слов-команд и
 * вежливых «пустых» слов: обычное предложение со словом «дальше» командой не считается.
 */
object VoiceCommandParser {
    private const val MAX_WORDS = 4

    private enum class Role { NEXT, PREVIOUS, PAUSE, PLAY, PARAGRAPH, FILLER }

    private val vocabulary: Map<String, Role> = buildMap {
        listOf("дальше", "вперед", "следующее", "следующий", "следующая", "next", "forward").forEach { put(it, Role.NEXT) }
        listOf("назад", "повтори", "предыдущее", "предыдущий", "предыдущая", "back", "previous").forEach { put(it, Role.PREVIOUS) }
        listOf("пауза", "стоп", "хватит", "тихо", "pause", "stop").forEach { put(it, Role.PAUSE) }
        listOf("продолжай", "продолжить", "читай", "играй", "play", "resume").forEach { put(it, Role.PLAY) }
        listOf("абзац", "paragraph").forEach { put(it, Role.PARAGRAPH) }
        listOf("давай", "пожалуйста", "ну", "ок", "окей", "please").forEach { put(it, Role.FILLER) }
    }

    /**
     * [speaking] — предложение, которое сейчас звучит: микрофон слышит и озвучку, поэтому фраза, дословно
     * входящая в него, командой не считается.
     */
    fun parse(phrase: String, speaking: String? = null): VoiceCommand? {
        val words = words(phrase)
        if (words.isEmpty() || words.size > MAX_WORDS) return null
        val roles = words.map { vocabulary[it] ?: return null }
        val directions = roles.filter { it != Role.FILLER && it != Role.PARAGRAPH }.toSet()
        if (directions.size != 1) return null
        if (speaking != null && isEcho(words.filter { vocabulary[it] != Role.FILLER }, words(speaking))) return null
        val paragraph = Role.PARAGRAPH in roles
        return when (directions.first()) {
            Role.NEXT -> if (paragraph) VoiceCommand.NEXT_PARAGRAPH else VoiceCommand.NEXT
            Role.PREVIOUS -> if (paragraph) VoiceCommand.PREVIOUS_PARAGRAPH else VoiceCommand.PREVIOUS
            Role.PAUSE -> VoiceCommand.PAUSE
            Role.PLAY -> VoiceCommand.PLAY
            else -> null
        }
    }

    private fun isEcho(phrase: List<String>, spoken: List<String>): Boolean {
        if (phrase.isEmpty() || spoken.size < phrase.size) return false
        return (0..spoken.size - phrase.size).any { start -> spoken.subList(start, start + phrase.size) == phrase }
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}

/** Отсекает повторы: одна и та же команда, услышанная дважды за [cooldownMs] (эхо, дубли распознавателя), выполняется один раз. */
class VoiceCommandGate(
    private val cooldownMs: Long = 1_500,
    private val clock: () -> Long = ::nowMillis
) {
    private var lastCommand: VoiceCommand? = null
    private var lastAt = 0L

    fun accept(command: VoiceCommand?): VoiceCommand? {
        if (command == null) return null
        val now = clock()
        if (command == lastCommand && now - lastAt < cooldownMs) return null
        lastCommand = command
        lastAt = now
        return command
    }
}

/** Выполняет команду в плеере озвучки. */
fun ReadAloudController.perform(command: VoiceCommand) {
    when (command) {
        VoiceCommand.NEXT -> nextSentence()
        VoiceCommand.PREVIOUS -> previousSentence()
        VoiceCommand.NEXT_PARAGRAPH -> nextParagraph()
        VoiceCommand.PREVIOUS_PARAGRAPH -> previousParagraph()
        VoiceCommand.PAUSE -> pause()
        VoiceCommand.PLAY -> play()
    }
}

/**
 * Распознаватель коротких фраз без интернета. Платформы используют встроенные системные распознаватели
 * (Android — `SpeechRecognizer` на устройстве, iOS — `SFSpeechRecognizer` с `requiresOnDeviceRecognition`).
 */
interface PhraseRecognizer {
    /** Поддерживается ли офлайн-распознавание речи на этом устройстве. */
    val isOfflineAvailable: Boolean

    /** Начинает слушать; [onPhrase] получает каждую распознанную фразу, [onError] — причину остановки. */
    fun start(onPhrase: (String) -> Unit, onError: (String) -> Unit)

    fun stop()
}

/** Системный офлайн-распознаватель платформы. */
expect fun createPhraseRecognizer(): PhraseRecognizer

/** Запрашивает разрешение на микрофон (системный диалог); true — разрешение есть. */
expect fun requestMicrophone(onResult: (Boolean) -> Unit)
