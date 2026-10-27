package com.localdoc.ai.domain

import kotlinx.serialization.Serializable

/**
 * Настройки отдельной книги поверх общих: пустое поле (null) означает «как в общих настройках».
 * Сюда входит только оформление страницы; режим чтения, озвучка и жесты остаются общими.
 */
@Serializable
data class BookOverride(
    val fontSize: Float? = null,
    val lineHeight: Float? = null,
    val font: ReaderFont? = null,
    val fontFile: String? = null,
    val theme: ReaderTheme? = null,
    val justify: Boolean? = null,
    val margin: Float? = null,
    val letterSpacing: Float? = null,
    val fontWeight: Int? = null
) {
    val isEmpty: Boolean get() = this == BookOverride()
}

/** Общие настройки с учётом настроек книги. */
fun ReaderSettings.applyOverride(o: BookOverride?): ReaderSettings {
    if (o == null) return this
    return copy(
        fontSize = o.fontSize ?: fontSize,
        lineHeight = o.lineHeight ?: lineHeight,
        font = o.font ?: font,
        fontFile = o.fontFile ?: fontFile,
        theme = o.theme ?: theme,
        justify = o.justify ?: justify,
        margin = o.margin ?: margin,
        letterSpacing = o.letterSpacing ?: letterSpacing,
        fontWeight = o.fontWeight ?: fontWeight
    )
}

/**
 * Разбирает правку, сделанную над «итоговыми» настройками книги: оформление, отличающееся от общего,
 * остаётся в настройках книги, всё остальное (режим чтения, озвучка…) уходит в общие.
 * Возвращает новые общие настройки и настройки книги.
 */
fun ReaderSettings.splitEdit(edited: ReaderSettings): Pair<ReaderSettings, BookOverride> {
    fun <T> diff(mine: T, theirs: T): T? = if (mine != theirs) mine else null
    val override = BookOverride(
        fontSize = diff(edited.fontSize, fontSize),
        lineHeight = diff(edited.lineHeight, lineHeight),
        font = diff(edited.font, font),
        fontFile = diff(edited.fontFile, fontFile),
        theme = diff(edited.theme, theme),
        justify = diff(edited.justify, justify),
        margin = diff(edited.margin, margin),
        letterSpacing = diff(edited.letterSpacing, letterSpacing),
        fontWeight = diff(edited.fontWeight, fontWeight)
    )
    val global = edited.copy(
        fontSize = fontSize, lineHeight = lineHeight, font = font, fontFile = fontFile, theme = theme,
        justify = justify, margin = margin, letterSpacing = letterSpacing, fontWeight = fontWeight
    )
    return global to override
}
