package com.localdoc.ai.engine

/** Коды результата localdoc_generate (см. cpp_core/bridge/localdoc_c_api.h). */
internal object GenerationStatus {
    const val OK = 0
    const val ERR_INVALID_ARGS = -1
    const val ERR_BUSY = -2
    const val ERR_TOKENIZE = -3
    const val ERR_PROMPT_TOO_LONG = -4
    const val ERR_DECODE = -5

    fun message(code: Int): String = when (code) {
        ERR_INVALID_ARGS -> "Некорректные параметры генерации"
        ERR_BUSY -> "Модель уже генерирует ответ — дождитесь окончания или нажмите «Стоп»"
        ERR_TOKENIZE -> "Не удалось токенизировать запрос"
        ERR_PROMPT_TOO_LONG -> "Запрос не помещается в контекст модели — сократите документ или вопрос"
        ERR_DECODE -> "Ошибка инференса (не хватает памяти?)"
        else -> "Неизвестная ошибка генерации ($code)"
    }

    /** Бросает исключение с понятным пользователю текстом, если генерация завершилась ошибкой. */
    fun ensureOk(code: Int) {
        check(code == OK) { message(code) }
    }
}
