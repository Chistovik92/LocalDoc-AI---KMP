package com.localdoc.ai.domain

import com.localdoc.ai.book.BookFormat

/** Прокрутка вниз дальше этого порога прячет панели (позиция списка: элемент * 10 000 + смещение). */
const val CHROME_HIDE_SCROLL = 400

/** Заметная прокрутка вверх дальше этого порога возвращает панели. */
const val CHROME_SHOW_SCROLL = 1200

/**
 * Включены ли экранные страницы («сколько влезет на экран»). Одно условие для книг и веб-страниц:
 * формат не влияет, страницы выключает только перевод (он идёт по порциям текста).
 */
@Suppress("UNUSED_PARAMETER")
fun screenPagesEnabled(paged: Boolean, translating: Boolean, format: BookFormat): Boolean = paged && !translating

/** Видимость панелей после прокрутки списка с позиции [last] на [pos]. */
fun chromeAfterScroll(visible: Boolean, last: Int, pos: Int): Boolean = when {
    pos > last + CHROME_HIDE_SCROLL -> false
    pos < last - CHROME_SHOW_SCROLL -> true
    else -> visible
}

/** Видимость панелей после касания по центру экрана. */
fun chromeAfterToggle(visible: Boolean): Boolean = !visible

/** Листание страницы — это чтение: панели (верхняя и нижняя вместе с плеером) прячутся. */
fun chromeAfterPageTurn(): Boolean = false

/** Старт озвучки — тоже чтение, панели прячутся; пауза и остановка видимость не меняют. */
fun chromeAfterPlaybackChange(visible: Boolean, playing: Boolean): Boolean = if (playing) false else visible

/** Нижняя панель (плеер, кнопки глав, страницы, ИИ) показывается ровно тогда же, когда и верхняя. */
fun bottomPanelVisible(uiVisible: Boolean): Boolean = uiVisible

/** Кнопки перехода между главами; [previousEnabled]/[nextEnabled] — доступность. */
data class ChapterButtons(val previousEnabled: Boolean, val nextEnabled: Boolean)

/**
 * Кнопки «к предыдущей/следующей главе» нужны при постраничном листании, когда у книги есть соседние главы
 * (веб-новеллы). null — кнопок нет; недоступная кнопка остаётся на месте, но отключена.
 */
fun chapterButtons(hasNavigation: Boolean, hasPrevious: Boolean, hasNext: Boolean, pagedListing: Boolean): ChapterButtons? =
    if (hasNavigation && pagedListing) ChapterButtons(previousEnabled = hasPrevious, nextEnabled = hasNext) else null
