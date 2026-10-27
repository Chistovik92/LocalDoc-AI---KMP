package com.localdoc.ai.notes

/** Подсвеченное в блоке место цитаты: диапазон символов блока, цвет и цитата, которой оно принадлежит. */
data class TextMark(val range: IntRange, val color: AnnotationColor, val annotationId: String)

/** Цитата под символом [offset] блока; при наложении — самая поздняя из списка. null — символ не подсвечен. */
fun List<TextMark>.markAt(offset: Int): TextMark? = lastOrNull { offset in it.range }
