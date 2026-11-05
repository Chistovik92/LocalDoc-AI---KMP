package com.localdoc.ai.tts

import com.localdoc.ai.net.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Когда таймер сна останавливает озвучку. */
sealed interface SleepMode {
    /** Через заданное число минут от запуска. */
    data class After(val minutes: Int) : SleepMode

    /** Когда дочитается текущая глава. */
    data object ChapterEnd : SleepMode
}

data class SleepTimerState(
    val mode: SleepMode? = null,
    /** Сколько осталось до остановки; для [SleepMode.ChapterEnd] не определено (0). */
    val remainingMs: Long = 0
)

/** Итог одного шага таймера. */
sealed interface SleepTick {
    data object Idle : SleepTick

    /** Таймер идёт; [volume] — громкость озвучки (1 — обычная, ниже — плавное затухание перед остановкой). */
    data class Running(val volume: Float) : SleepTick

    /** Время вышло: озвучку нужно остановить. Возвращается один раз, дальше таймер снова [Idle]. */
    data object Expired : SleepTick
}

/** Затухание громкости перед остановкой. */
object SleepFade {
    /** За сколько до остановки по времени громкость начинает падать. */
    const val FADE_MS = 30_000L

    /** Громкость не доходит до нуля, пока озвучка не остановлена: тишина — признак сбоя, а не затухания. */
    const val MIN_VOLUME = 0.08f

    /** Сколько последних предложений главы звучат тише при режиме «до конца главы». */
    const val FADE_SENTENCES = 3

    fun byTime(remainingMs: Long, fadeMs: Long = FADE_MS): Float {
        if (remainingMs >= fadeMs) return 1f
        val ratio = remainingMs.coerceAtLeast(0).toFloat() / fadeMs
        return MIN_VOLUME + (1f - MIN_VOLUME) * ratio
    }

    fun bySentences(sentencesLeft: Int): Float {
        if (sentencesLeft > FADE_SENTENCES) return 1f
        val ratio = (sentencesLeft - 1).coerceAtLeast(0).toFloat() / FADE_SENTENCES
        return MIN_VOLUME + (1f - MIN_VOLUME) * ratio
    }
}

/**
 * Таймер сна — чистая логика без корутин: время приходит из [clock], шаги делает [tick].
 * Секундами и озвучкой управляет [SleepTimerController].
 */
class SleepTimer(private val clock: () -> Long = ::nowMillis) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var deadline = 0L

    fun startAfter(minutes: Int) {
        if (minutes <= 0) return
        deadline = clock() + minutes * MINUTE_MS
        _state.value = SleepTimerState(SleepMode.After(minutes), minutes * MINUTE_MS)
    }

    fun startAtChapterEnd() {
        deadline = 0
        _state.value = SleepTimerState(SleepMode.ChapterEnd)
    }

    /** Добавляет время к идущему отсчёту; режим «до конца главы» не меняется. */
    fun extend(minutes: Int) {
        val mode = _state.value.mode as? SleepMode.After ?: return
        if (minutes <= 0) return
        deadline += minutes * MINUTE_MS
        _state.value = SleepTimerState(mode.copy(minutes = mode.minutes + minutes), (deadline - clock()).coerceAtLeast(0))
    }

    fun cancel() {
        deadline = 0
        _state.value = SleepTimerState()
    }

    /** Таймер сделал своё дело (озвучка остановлена на конце главы): сбрасывается без события. */
    fun finish() = cancel()

    /** [sentencesLeft] — сколько предложений до конца главы (нужно только для режима «до конца главы»). */
    fun tick(sentencesLeft: Int? = null): SleepTick {
        val mode = _state.value.mode ?: return SleepTick.Idle
        if (mode is SleepMode.ChapterEnd) {
            return SleepTick.Running(sentencesLeft?.let(SleepFade::bySentences) ?: 1f)
        }
        val remaining = deadline - clock()
        if (remaining <= 0) {
            cancel()
            return SleepTick.Expired
        }
        _state.value = SleepTimerState(mode, remaining)
        return SleepTick.Running(SleepFade.byTime(remaining))
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}

/**
 * Связывает [SleepTimer] с плеером: раз в полсекунды обновляет громкость и по истечении времени останавливает
 * озвучку (позиция сохраняется). В режиме «до конца главы» голос не объявляет конец главы и сразу замолкает.
 */
class SleepTimerController(
    private val player: ReadAloudController,
    private val scope: CoroutineScope,
    val timer: SleepTimer = SleepTimer()
) {
    private var job: Job? = null

    val state: StateFlow<SleepTimerState> get() = timer.state

    fun startAfter(minutes: Int) {
        timer.startAfter(minutes)
        player.quietChapterEnd = false
        ensureRunning()
    }

    fun startAtChapterEnd() {
        timer.startAtChapterEnd()
        player.quietChapterEnd = true
        ensureRunning()
    }

    fun extend(minutes: Int) = timer.extend(minutes)

    fun cancel() {
        timer.cancel()
        release()
    }

    private fun release() {
        job?.cancel()
        job = null
        player.quietChapterEnd = false
        player.setVolume(1f)
    }

    private fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val s = player.state.value
                // Озвучку остановили вручную или она дочиталась до конца главы — таймер выполнил задачу
                if (!s.active || s.sleepStopped || s.stoppedAt != null) {
                    timer.finish()
                    release()
                    return@launch
                }
                val left = if (timer.state.value.mode == SleepMode.ChapterEnd) player.sentencesLeftInChapter() else null
                when (val tick = timer.tick(left)) {
                    is SleepTick.Running -> if (s.playing) player.setVolume(tick.volume)
                    SleepTick.Expired -> {
                        player.sleepStop()
                        release()
                        return@launch
                    }
                    SleepTick.Idle -> {
                        release()
                        return@launch
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 500L
    }
}
