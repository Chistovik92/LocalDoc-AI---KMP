package com.localdoc.ai.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockMeasure
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.PageMeasurer
import com.localdoc.ai.book.PageSpec
import com.localdoc.ai.domain.ReaderFont
import com.localdoc.ai.domain.ReaderSettings
import kotlin.math.max
import kotlin.math.min

/*
 * Типографика читалки в одном месте: один и тот же стиль и отступы нужны и при отрисовке блока (BlockText),
 * и при измерении страницы (ComposePageMeasurer) — иначе высота страницы разойдётся с реальной.
 */

internal const val LIST_BULLET = "•  "

/** Максимальная ширина колонки текста, dp. */
internal const val READER_COLUMN_MAX_DP = 720

internal const val CODE_PADDING_DP = 12f
internal const val QUOTE_INDENT_DP = 12f
internal const val LIST_INDENT_DP = 16f

/** Запас под подпись картинки и вертикальные отступы вокруг неё, dp. */
private const val IMAGE_CAPTION_DP = 44f
private const val IMAGE_PADDING_DP = 16f

/** Пока размер картинки неизвестен, ей отводится место 4:3 по ширине. */
private const val IMAGE_ASPECT = 0.75f

/** Отступы блока, dp: сверху и снизу снаружи текста, слева и справа внутри рамки (цитата, код, список). */
internal data class BlockInsets(val top: Float, val bottom: Float, val start: Float, val end: Float)

internal fun readerFamily(settings: ReaderSettings, custom: FontFamily?): FontFamily =
    custom ?: if (settings.font == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif

/** Стиль текста блока без цвета, выключки и переносов. */
internal fun blockBaseStyle(block: Block, settings: ReaderSettings, family: FontFamily): TextStyle {
    val weight = FontWeight(settings.fontWeight.coerceIn(100, 900))
    val spacing = settings.letterSpacing.sp
    val base = settings.fontSize
    return when (block.type) {
        BlockType.HEADING -> TextStyle(
            fontFamily = family,
            fontSize = (base * if (block.level <= 1) 1.5f else 1.25f).sp,
            fontWeight = FontWeight((settings.fontWeight + 200).coerceIn(600, 900)),
            letterSpacing = spacing,
            lineHeight = (base * 1.9f).sp
        )
        BlockType.QUOTE -> TextStyle(fontFamily = family, fontWeight = weight, letterSpacing = spacing, fontSize = base.sp, fontStyle = FontStyle.Italic, lineHeight = (base * settings.lineHeight).sp)
        BlockType.CODE -> TextStyle(fontFamily = FontFamily.Monospace, fontSize = (base * 0.82f).sp, lineHeight = (base * 1.35f).sp)
        BlockType.LIST_ITEM, BlockType.PARAGRAPH, BlockType.IMAGE -> TextStyle(fontFamily = family, fontWeight = weight, letterSpacing = spacing, fontSize = base.sp, lineHeight = (base * settings.lineHeight).sp)
    }
}

/** Полный стиль блока, как его рисует читалка (выключка, умные переносы). */
internal fun blockTextStyle(block: Block, settings: ReaderSettings, family: FontFamily, color: Color): TextStyle =
    blockBaseStyle(block, settings, family).copy(
        color = color,
        textAlign = if (settings.justify && block.type == BlockType.PARAGRAPH) TextAlign.Justify else TextAlign.Start,
        lineBreak = if (settings.justify && settings.smartJustify) LineBreak.Paragraph else LineBreak.Unspecified,
        hyphens = if (settings.justify && settings.smartJustify) Hyphens.Auto else Hyphens.Unspecified
    )

/** Отступы блока, dp. Для кода вертикальные 12 dp — внутри его рамки. */
internal fun blockInsets(block: Block, settings: ReaderSettings): BlockInsets {
    val top = when (block.type) {
        BlockType.HEADING -> if (block.level <= 1) 28f else 18f
        BlockType.QUOTE, BlockType.CODE -> 6f
        else -> 0f
    }
    val bottom = if (block.type == BlockType.HEADING) 10f else settings.fontSize * 0.7f
    return when (block.type) {
        BlockType.CODE -> BlockInsets(top + CODE_PADDING_DP, bottom + CODE_PADDING_DP, CODE_PADDING_DP, CODE_PADDING_DP)
        BlockType.QUOTE -> BlockInsets(top, bottom, QUOTE_INDENT_DP, 0f)
        BlockType.LIST_ITEM -> BlockInsets(top, bottom, block.level.coerceAtMost(4) * LIST_INDENT_DP, 0f)
        else -> BlockInsets(top, bottom, 0f, 0f)
    }
}

private fun imageExtraPx(block: Block, density: Density): Float =
    with(density) { (IMAGE_PADDING_DP + if (block.text.isNotBlank()) IMAGE_CAPTION_DP else 0f).dp.toPx() }

/** Высота места под картинку на странице, px: сама картинка по ширине, отступы и подпись; не выше страницы. */
internal fun imageSlotHeightPx(block: Block, spec: PageSpec, density: Density): Float =
    min(spec.width * IMAGE_ASPECT + imageExtraPx(block, density), spec.height)

/** Часть места, остающаяся самой картинке, px. */
internal fun imageBodyHeightPx(block: Block, slotPx: Float, density: Density): Float =
    max(slotPx - imageExtraPx(block, density), 1f)

/** Ширина колонки текста внутри области (без полей страницы), px. */
internal fun pageContentWidthPx(areaWidthPx: Float, settings: ReaderSettings, density: Density): Float =
    max(min(areaWidthPx, with(density) { READER_COLUMN_MAX_DP.dp.toPx() }) - with(density) { (settings.margin * 2).dp.toPx() }, 1f)

/**
 * Измеритель страниц на Compose [TextMeasurer]: те же стили и отступы, что и при отрисовке
 * ([blockTextStyle], [blockInsets]). Один экземпляр — для одного потока (внутри кэш измерителя текста).
 */
internal class ComposePageMeasurer(
    private val measurer: TextMeasurer,
    private val settings: ReaderSettings,
    private val family: FontFamily,
    private val density: Density
) : PageMeasurer {

    override fun measure(block: Block, spec: PageSpec): BlockMeasure {
        if (block.type == BlockType.IMAGE) {
            return BlockMeasure(0f, 0f, floatArrayOf(imageSlotHeightPx(block, spec, density)), intArrayOf(0), atomic = true)
        }
        val insets = blockInsets(block, settings)
        val (top, bottom, start, end) = with(density) {
            listOf(insets.top.dp.toPx(), insets.bottom.dp.toPx(), insets.start.dp.toPx(), insets.end.dp.toPx())
        }
        val width = max(spec.width - start - end, 1f)
        val bullet = if (block.type == BlockType.LIST_ITEM) LIST_BULLET.length else 0
        val text = buildBlockText(block, null, Color.Unspecified, emptyList())
        val layout = measurer.measure(
            text = if (bullet > 0) AnnotatedString(LIST_BULLET) + text else text,
            style = blockTextStyle(block, settings, family, Color.Unspecified),
            constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1))
        )
        val count = layout.lineCount.coerceAtLeast(1)
        val heights = FloatArray(count) { layout.getLineBottom(it) - layout.getLineTop(it) }
        val starts = IntArray(count) { if (it == 0) 0 else (layout.getLineStart(it) - bullet).coerceAtLeast(0) }
        return BlockMeasure(top, bottom, heights, starts)
    }
}
