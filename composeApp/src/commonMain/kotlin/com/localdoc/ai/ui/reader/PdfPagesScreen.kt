package com.localdoc.ai.ui.reader

import com.localdoc.ai.resources.back
import com.localdoc.ai.resources.page
import com.localdoc.ai.resources.text_mode
import com.localdoc.ai.resources.the_page_did_not_open
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.pdf.PdfPageSource
import com.localdoc.ai.book.pdf.blockOfPdfPage
import com.localdoc.ai.book.pdf.pdfPageOfBlock
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.images.decodeImageBitmap
import com.localdoc.ai.platform.IoDispatcher
import com.localdoc.ai.platform.PdfPageRenderer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val RENDER_OVERSAMPLE = 1.5f
private const val MAX_RENDER_WIDTH = 2200
private const val MIN_RENDER_WIDTH = 320

/**
 * PDF страницами-картинками: одна страница в памяти, соседние подгружаются заранее, кэш ограничен.
 * Двойное касание и щипок — увеличение, касание — показать или скрыть панели. Если документ не открылся,
 * сразу вызывается [onShowText] (режим «текст» остаётся всегда).
 */
@Composable
fun PdfPagesScreen(
    book: Book,
    startBlock: Int,
    onProgress: (Int) -> Unit,
    onBack: () -> Unit,
    onShowText: () -> Unit
) {
    val fileName = book.pdfFile
    val source = remember(fileName) {
        fileName?.let { name ->
            runCatching { PdfPageSource(PdfPageRenderer(AppGraph.files.appFilePath(name)), IoDispatcher) }.getOrNull()
        }
    }
    DisposableEffect(source) { onDispose { source?.close() } }
    if (source == null || source.pageCount <= 0) {
        LaunchedEffect(Unit) { onShowText() }
        return
    }

    val pageCount = source.pageCount
    val pagerState = rememberPagerState(initialPage = book.pdfPageOfBlock(startBlock, pageCount), pageCount = { pageCount })
    var zoomed by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var jump by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { onProgress(book.blockOfPdfPage(it, pageCount)) }
    }
    LaunchedEffect(jump) {
        jump?.let { pagerState.scrollToPage(it) }
        jump = null
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1B1B1B))) {
        val density = LocalDensity.current.density
        val widthPx = (maxWidth.value * density * RENDER_OVERSAMPLE).toInt().coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH)

        // Соседние страницы готовятся заранее; кэш при этом не растёт сверх лимита
        LaunchedEffect(pagerState.currentPage, widthPx) {
            val page = pagerState.currentPage
            source.page(page + 1, widthPx)
            source.page(page - 1, widthPx)
        }

        HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { index ->
            PdfPageView(source, index, widthPx, onZoomChanged = { zoomed = it }, onTap = { controls = !controls })
        }

        if (controls) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            ) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back)) }
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = onShowText) { Icon(Icons.Default.TextFields, contentDescription = stringResource(Res.string.text_mode)) }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    var dragging by remember { mutableFloatStateOf(-1f) }
                    val shown = if (dragging >= 0f) dragging.toInt() else pagerState.currentPage
                    Text("${shown + 1} / $pageCount", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = if (dragging >= 0f) dragging else pagerState.currentPage.toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            jump = dragging.toInt().coerceIn(0, pageCount - 1)
                            dragging = -1f
                        },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat()
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageView(source: PdfPageSource, index: Int, widthPx: Int, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit) {
    var bitmap by remember(index, widthPx) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(index, widthPx) { mutableStateOf(false) }
    LaunchedEffect(index, widthPx) {
        val bytes = source.page(index, widthPx)
        val decoded = bytes?.let { withContext(IoDispatcher) { decodeImageBitmap(it, widthPx) } }
        bitmap = decoded
        failed = decoded == null
    }

    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    DisposableEffect(index) { onDispose { onZoomChanged(false) } }

    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = DOUBLE_TAP_ZOOM
                        onZoomChanged(scale > 1f)
                    }
                )
            }
            .pointerInput(index) {
                // Одним пальцем без увеличения жест не трогаем — иначе страницы не листались бы
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            offset = if (scale > 1f) {
                                val maxX = size.width * (scale - 1f) / 2f
                                val maxY = size.height * (scale - 1f) / 2f
                                Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            } else Offset.Zero
                            onZoomChanged(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = stringResource(Res.string.page, index + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y
                )
            )
            failed -> Icon(Icons.Default.BrokenImage, contentDescription = stringResource(Res.string.the_page_did_not_open), tint = Color.White)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}
