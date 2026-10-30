package com.localdoc.ai.engine

import com.localdoc.ai.i18n.L10n
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.gen_err_busy
import com.localdoc.ai.resources.gen_err_decode
import com.localdoc.ai.resources.gen_err_invalid_args
import com.localdoc.ai.resources.gen_err_prompt_too_long
import com.localdoc.ai.resources.gen_err_tokenize
import com.localdoc.ai.resources.gen_err_unknown
import org.jetbrains.compose.resources.StringResource

/** Коды результата localdoc_generate (см. cpp_core/bridge/localdoc_c_api.h). */
internal object GenerationStatus {
    const val OK = 0
    const val ERR_INVALID_ARGS = -1
    const val ERR_BUSY = -2
    const val ERR_TOKENIZE = -3
    const val ERR_PROMPT_TOO_LONG = -4
    const val ERR_DECODE = -5

    fun resource(code: Int): StringResource = when (code) {
        ERR_INVALID_ARGS -> Res.string.gen_err_invalid_args
        ERR_BUSY -> Res.string.gen_err_busy
        ERR_TOKENIZE -> Res.string.gen_err_tokenize
        ERR_PROMPT_TOO_LONG -> Res.string.gen_err_prompt_too_long
        ERR_DECODE -> Res.string.gen_err_decode
        else -> Res.string.gen_err_unknown
    }

    /** Локализованное сообщение об ошибке генерации. */
    suspend fun message(code: Int): String = when (code) {
        ERR_INVALID_ARGS, ERR_BUSY, ERR_TOKENIZE, ERR_PROMPT_TOO_LONG, ERR_DECODE -> L10n.get(resource(code))
        else -> L10n.get(Res.string.gen_err_unknown, code.toString())
    }

    /** Бросает исключение с понятным пользователю текстом, если генерация завершилась ошибкой. */
    suspend fun ensureOk(code: Int) {
        if (code != OK) check(false) { message(code) }
    }
}
