package com.localdoc.ai.ui.reader

import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.back
import com.localdoc.ai.resources.page
import com.localdoc.ai.resources.pdf_copy_missing
import com.localdoc.ai.resources.pdf_no_pages
import com.localdoc.ai.resources.pdf_no_text_layer_text
import com.localdoc.ai.resources.pdf_no_text_layer_title
import com.localdoc.ai.resources.pdf_open_failed
import com.localdoc.ai.resources.pdf_pages_overview
import com.localdoc.ai.resources.pdf_read_pages
import com.localdoc.ai.resources.pdf_recognize_text
import com.localdoc.ai.resources.pdf_recognizing
import com.localdoc.ai.resources.repeat
import com.localdoc.ai.resources.text_mode
import com.localdoc.ai.resources.the_page_did_not_open
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.pdf.PdfPageResult
import com.localdoc.ai.book.pdf.PdfPageSource
import com.localdoc.ai.book.pdf.PdfRenderSize
import com.localdoc.ai.book.pdf.blockOfPdfPage
import com.localdoc.ai.book.pdf.pdfPageOfBlock
import com.localdoc.ai.book.pdf.zoomRenderWidth
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.images.decodeImageBitmap
import com.localdoc.ai.platform.DebugLog
import com.localdoc.ai.platform.IoDispatcher
import com.localdoc.ai.platform.PdfPageRenderer
import com.localdoc.ai.platform.appMaxMemoryBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val RENDER_OVERSAMPLE = 1.25f
private const val MAX_RENDER_WIDTH = 2200
private const val MIN_RENDER_WIDTH = 320

/** Абсолютный потолок перерисовки при зуме: дальше буквы не становятся читабельнее, а память тратится. */
private const val MAX_ZOOM_RENDER_WIDTH = 3200

/** Ширина рендера миниатюр в обзоре страниц. */
private const val THUMB_WIDTH = 360

/** Предел ширины рендера от размера кучи: страница ARGB_8888 занимает ширину×высоту×4,
 *  устройству с малой кучей слишком широкий рендер устраивает OOM на каждой странице. */
private fun maxRenderWidth(): Int =
    (appMaxMemoryBytes / (160L * 1024 * 1024) * 1100L)
        .coerceIn(MIN_RENDER_WIDTH.toLong(), MAX_RENDER_WIDTH.toLong()).toInt()

/** Потолок перерисовки при зуме: вдвое выше базового предела, но не больше абсолютного. */
private fun zoomMaxRenderWidth(): Int = (maxRenderWidth() * 2).coerceAtMost(MAX_ZOOM_RENDER_WIDTH)

/** Бюджет пикселей страницы, перерисованной при зуме: страница в кадре одна, поэтому острота
 *  важнее экономии. Масштабируется от кучи — слабые устройства получают меньше и не ловят OOM. */
private fun zoomMaxPixels(): Long =
    (appMaxMemoryBytes / (32L * 1024 * 1024)).coerceIn(4_000_000L, 12_000_000L)

/** Почему документ не открылся страницами: у каждой причины своё пояснение для пользователя. */
private enum class PdfOpenFailure { COPY_MISSING, OPEN_FAILED, NO_PAGES }

/**
 * PDF страницами-картинками: одна страница в памяти, соседние подгружаются заранее, кэш ограничен.
 * Щипок приближает страницу вокруг пальцев и перерисовывает её резче, двойное касание — в точку
 * касания, касание — показать или скрыть панели, кнопка сетки — обзор всех страниц.
 * Если документ не открылся, видна причина и кнопки: повторить или режим «текст».
 */
@Composable
fun PdfPagesScreen(
    book: Book,
    startBlock: Int,
    onProgress: (Int) -> Unit,
    onBack: () -> Unit,
    onShowText: () -> Unit,
    onRunOcr: () -> Unit,
    ocrRunning: Boolean
) {
    val fileName = book.pdfFile
    // Документ открывается вне главного потока: открытие большого PDF иначе подвесило бы интерфейс
    var openedSource by remember { mutableStateOf<PdfPageSource?>(null) }
    var openFailure by remember { mutableStateOf<PdfOpenFailure?>(null) }
    var openAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(fileName, openAttempt) {
        // Повтор или смена файла: прежний источник закрывается сразу, нативные ресурсы не копятся
        openedSource?.close()
        openedSource = null
        openFailure = null
        val name = fileName
        if (name == null) {
            openFailure = PdfOpenFailure.COPY_MISSING
            return@LaunchedEffect
        }
        val source = withContext(IoDispatcher) {
            // Локальная копия не найдена или пуста: не тратим время на открытие.
            // Это имя в хранилище приложения (filesDir/books), а не путь/URI — sizeOf() здесь не годится
            if (AppGraph.files.appFileSize(name) <= 0L) {
                DebugLog.e("PDF copy missing name=$name")
                null
            } else try {
                val opened = PdfPageSource(PdfPageRenderer(AppGraph.files.appFilePath(name)), IoDispatcher)
                // Эффект мог отмениться, пока документ открывался: не оставляем нативные ресурсы висеть
                if (!currentCoroutineContext().isActive) {
                    opened.close()
                    null
                } else opened
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                DebugLog.e("PDF open failed name=$name", t)
                null
            }
        }
        when {
            source == null -> openFailure = PdfOpenFailure.OPEN_FAILED
            source.pageCount <= 0 -> {
                DebugLog.e("PDF has no pages name=$name")
                source.close()
                openFailure = PdfOpenFailure.NO_PAGES
            }
            else -> openedSource = source
        }
    }
    // Экран закрылся — документ закрывается; источник живёт ровно столько, сколько экран
    DisposableEffect(Unit) { onDispose { openedSource?.close() } }

    val source = openedSource
    if (source == null) {
        val failure = openFailure
        if (failure != null) PdfOpenFailureView(failure, onRetry = { openAttempt++ }, onShowText = onShowText)
        else Box(Modifier.fillMaxSize().background(Color(0xFF1B1B1B)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val pageCount = source.pageCount
    val pagerState = rememberPagerState(initialPage = book.pdfPageOfBlock(startBlock, pageCount), pageCount = { pageCount })
    var zoomed by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var jump by remember { mutableStateOf<Int?>(null) }
    var showOverview by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { onProgress(book.blockOfPdfPage(it, pageCount)) }
    }
    LaunchedEffect(jump) {
        jump?.let { pagerState.scrollToPage(it) }
        jump = null
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1B1B1B))) {
        val density = LocalDensity.current.density
        val widthPx = (maxWidth.value * density * RENDER_OVERSAMPLE).toInt().coerceIn(MIN_RENDER_WIDTH, maxRenderWidth())

        // Соседние страницы готовятся заранее; кэш при этом не растёт сверх лимита
        LaunchedEffect(pagerState.currentPage, widthPx) {
            val page = pagerState.currentPage
            source.page(page + 1, widthPx)
            source.page(page - 1, widthPx)
        }

        HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { index ->
            PdfPageView(
                source, index, widthPx,
                onZoomChanged = { zoomed = it },
                onTap = { controls = !controls },
                onShowText = onShowText
            )
        }

        if (controls) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            ) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back)) }
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showOverview = true }) {
                        Icon(Icons.Default.GridView, contentDescription = stringResource(Res.string.pdf_pages_overview))
                    }
                    if (ocrRunning) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onRunOcr) { Text(stringResource(Res.string.pdf_recognize_text)) }
                    }
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

        // Обзор всех страниц: миниатюры, нажатие открывает страницу
        if (showOverview) {
            PdfPagesOverviewSheet(
                source = source,
                pageCount = pageCount,
                currentPage = pagerState.currentPage,
                onDismiss = { showOverview = false },
                onOpenPage = { page ->
                    jump = page
                    showOverview = false
                }
            )
        }

        // Распознавание текста (OCR) идёт в фоне; поверх страниц — индикатор, чтобы не листали во время него
        if (ocrRunning) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        stringResource(Res.string.pdf_recognizing),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

/** Документ не открылся страницами: причина видна, повтор и режим «текст» — под рукой. */
@Composable
private fun PdfOpenFailureView(failure: PdfOpenFailure, onRetry: () -> Unit, onShowText: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF1B1B1B)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(
                when (failure) {
                    PdfOpenFailure.COPY_MISSING -> stringResource(Res.string.pdf_copy_missing)
                    PdfOpenFailure.OPEN_FAILED -> stringResource(Res.string.pdf_open_failed)
                    PdfOpenFailure.NO_PAGES -> stringResource(Res.string.pdf_no_pages)
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.repeat)) }
            TextButton(onClick = onShowText) { Text(stringResource(Res.string.text_mode)) }
        }
    }
}

@Composable
private fun PdfPageView(
    source: PdfPageSource,
    index: Int,
    widthPx: Int,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onShowText: () -> Unit
) {
    // Битмап переживает смену ширины рендера при зуме: старая картинка висит, пока рисуется резче
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember(index) { mutableStateOf<String?>(null) }
    var attempt by remember(index) { mutableIntStateOf(0) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    val didNotOpen = stringResource(Res.string.the_page_did_not_open)
    val repeatLabel = stringResource(Res.string.repeat)
    val textModeLabel = stringResource(Res.string.text_mode)

    // Зум перерисовывает страницу ступенями (2×, 3×… базовой ширины): резкость держится, память не течёт
    val renderWidth = zoomRenderWidth(scale, widthPx, zoomMaxRenderWidth())
    LaunchedEffect(index, renderWidth, attempt) {
        var width = renderWidth
        while (true) {
            val budget = if (width > widthPx) zoomMaxPixels() else PdfRenderSize.MAX_PIXELS
            val reason: String = when (val result = source.page(index, width, budget)) {
                is PdfPageResult.Ready -> {
                    // Страница уже нарисована нужного размера: декодируем без уменьшения —
                    // decodeImageBitmap режет длинную сторону, а не ширину, и мылил бы портретные страницы
                    val decoded = withContext(IoDispatcher) {
                        decodeImageBitmap(result.bytes, PdfRenderSize.MAX_SIDE, preferRgb565 = true)
                    }
                    if (decoded != null) {
                        DebugLog.d("PDF page ready index=$index width=$width bytes=${result.bytes.size}")
                        bitmap = decoded
                        failure = null
                        return@LaunchedEffect
                    }
                    didNotOpen
                }
                is PdfPageResult.Failed -> {
                    DebugLog.e("PDF page failed index=$index reason=${result.reason}")
                    result.reason
                }
            }
            // Декод не прошёл (обычно не хватает памяти): повторяем вдвое уже — лучше хуже, чем ничего
            val narrower = (width / 2).coerceAtLeast(MIN_RENDER_WIDTH)
            if (narrower >= width) {
                if (bitmap == null) failure = reason
                return@LaunchedEffect
            }
            DebugLog.d("PDF page retry narrower index=$index width=$width -> $narrower")
            width = narrower
        }
    }

    DisposableEffect(index) { onDispose { onZoomChanged(false) } }

    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Приближаем вокруг точки касания, а не центра страницы
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = DOUBLE_TAP_ZOOM
                            offset = pos - center - (pos - center) * DOUBLE_TAP_ZOOM
                        }
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
                            val oldScale = scale
                            val newScale = (oldScale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val centroid = event.calculateCentroid()
                            // Точка страницы под пальцами остаётся под пальцами: смещение считается вокруг центроида
                            var newOffset = if (oldScale > 1f || newScale > 1f)
                                (centroid - center - (centroid - center - offset) * (newScale / oldScale)) + event.calculatePan()
                            else Offset.Zero
                            if (newScale > 1f) {
                                val maxX = size.width * (newScale - 1f) / 2f
                                val maxY = size.height * (newScale - 1f) / 2f
                                newOffset = Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
                            } else newOffset = Offset.Zero
                            scale = newScale
                            offset = newOffset
                            onZoomChanged(newScale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        val failureText = failure
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = stringResource(Res.string.page, index + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y
                )
            )
            failureText != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                // Причина сбоя видна пользователю: понятно, что это не зависание — файл зашифрован или кончилась память
                Text(
                    failureText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = { attempt++ }) { Text(repeatLabel) }
                TextButton(onClick = onShowText) { Text(textModeLabel) }
            }
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Обзор всех страниц: сетка миниатюр, нажатие открывает выбранную. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfPagesOverviewSheet(
    source: PdfPageSource,
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onOpenPage: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(Res.string.pdf_pages_overview),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pageCount) { page ->
                    PdfThumbnail(source, page, selected = page == currentPage, onClick = { onOpenPage(page) })
                }
            }
        }
    }
}

@Composable
private fun PdfThumbnail(source: PdfPageSource, index: Int, selected: Boolean, onClick: () -> Unit) {
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index) {
        when (val result = source.page(index, THUMB_WIDTH)) {
            is PdfPageResult.Ready -> {
                // Миниатюра рисуется узкой; декодируем с запасом по длинной стороне — без мыла
                bitmap = withContext(IoDispatcher) { decodeImageBitmap(result.bytes, THUMB_WIDTH * 2) }
            }
            is PdfPageResult.Failed -> failed = true
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    image,
                    contentDescription = stringResource(Res.string.page, index + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                !failed -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> {}
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** PDF-скан без текстового слоя: вместо пустой читалки — объяснение и выбор: страницы-картинки или OCR. */
@Composable
fun PdfNoTextLayerScreen(
    ocrRunning: Boolean,
    onReadPages: () -> Unit,
    onRunOcr: () -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(Res.string.pdf_no_text_layer_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(Res.string.pdf_no_text_layer_text),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (ocrRunning) {
                CircularProgressIndicator(Modifier.padding(top = 20.dp))
            } else {
                Button(onClick = onReadPages, modifier = Modifier.padding(top = 20.dp)) {
                    Text(stringResource(Res.string.pdf_read_pages))
                }
                OutlinedButton(onClick = onRunOcr, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(Res.string.pdf_recognize_text))
                }
            }
        }
    }
}
