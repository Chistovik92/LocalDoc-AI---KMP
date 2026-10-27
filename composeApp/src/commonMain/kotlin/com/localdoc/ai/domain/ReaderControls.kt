package com.localdoc.ai.domain

import kotlin.math.abs

/** Что делает нажатие или свайп в читалке. */
enum class TapAction { PREVIOUS, NEXT, MENU }

/** Как листается: прокруткой списка, сменой страницы или никак (край книги). */
enum class PageTurn { SCROLL, PAGE, NONE }

const val MIN_READER_FONT_SIZE = 13f
const val MAX_READER_FONT_SIZE = 32f
const val MIN_AUTO_SCROLL_LPM = 5
const val MAX_AUTO_SCROLL_LPM = 120
const val MIN_ZONE_WIDTH = 0.1f
const val MAX_ZONE_WIDTH = 0.45f
const val MIN_BRIGHTNESS = 0.02f
const val MIN_EINK_REFRESH_PAGES = 2
const val MAX_EINK_REFRESH_PAGES = 20

private const val SWIPE_DOMINANCE = 2f

/** Действие по нажатию в точке [x] экрана шириной [width]: края листают, центр показывает меню. */
fun tapAction(x: Float, width: Float, zones: TapZones, zoneWidth: Float): TapAction {
    if (width <= 0f || zones == TapZones.MENU_ONLY) return TapAction.MENU
    val edge = zoneWidth.coerceIn(MIN_ZONE_WIDTH, MAX_ZONE_WIDTH)
    val left = if (zones == TapZones.EDGES) TapAction.PREVIOUS else TapAction.NEXT
    val right = if (zones == TapZones.EDGES) TapAction.NEXT else TapAction.PREVIOUS
    return when {
        x < width * edge -> left
        x > width * (1f - edge) -> right
        else -> TapAction.MENU
    }
}

/**
 * Что делать при листании [forward]: пока список можно прокрутить в нужную сторону — прокручиваем его
 * (длинная страница или сплошной режим), иначе в постраничном режиме меняем страницу.
 */
fun pageTurn(forward: Boolean, canScrollList: Boolean, pagedDisplay: Boolean, page: Int, pageCount: Int): PageTurn = when {
    canScrollList -> PageTurn.SCROLL
    pagedDisplay && forward && page < pageCount - 1 -> PageTurn.PAGE
    pagedDisplay && !forward && page > 0 -> PageTurn.PAGE
    else -> PageTurn.NONE
}

/** Свайп по горизонтали: влево — вперёд, вправо — назад; null — жест слишком короткий или больше вертикальный. */
fun swipeAction(dx: Float, dy: Float, minDistance: Float): TapAction? {
    if (abs(dx) < minDistance || abs(dx) < abs(dy) * SWIPE_DOMINANCE) return null
    return if (dx < 0f) TapAction.NEXT else TapAction.PREVIOUS
}

/** Размер шрифта после щипка: [zoom] — во сколько раз раздвинулись пальцы. */
fun pinchFontSize(current: Float, zoom: Float): Float {
    if (zoom <= 0f) return current
    return (current * zoom).coerceIn(MIN_READER_FONT_SIZE, MAX_READER_FONT_SIZE)
}

/** Яркость после вертикального перетаскивания: вверх (отрицательный [dragPx]) — ярче, на всю высоту экрана — от нуля до единицы. */
fun brightnessAfterDrag(current: Float, dragPx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return (current - dragPx / heightPx).coerceIn(MIN_BRIGHTNESS, 1f)
}

/** На сколько пикселей прокрутить за [elapsedMs] мс при скорости [linesPerMinute] строк в минуту. */
fun autoScrollDelta(linesPerMinute: Int, lineHeightPx: Float, elapsedMs: Long): Float {
    if (linesPerMinute <= 0 || lineHeightPx <= 0f || elapsedMs <= 0L) return 0f
    return linesPerMinute.coerceIn(MIN_AUTO_SCROLL_LPM, MAX_AUTO_SCROLL_LPM) * lineHeightPx * elapsedMs / 60_000f
}

/** Анимация листания: выключена настройкой, режимом e-ink и системной настройкой «уменьшить анимацию». */
fun pageAnimationEnabled(setting: Boolean, eInk: Boolean, reducedMotion: Boolean): Boolean = setting && !eInk && !reducedMotion

/** После [turns]-го перелистывания нужно полностью обновить экран e-ink (каждые [every] страниц; 0 — не нужно). */
fun needsFullRefresh(turns: Int, every: Int): Boolean = every > 0 && turns > 0 && turns % every == 0
