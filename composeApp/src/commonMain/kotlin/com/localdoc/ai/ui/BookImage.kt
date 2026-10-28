package com.localdoc.ai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph

/** Состояние ленивой загрузки картинки. */
private sealed interface ImageState {
    data object Loading : ImageState
    data object Failed : ImageState
    class Ready(val bitmap: ImageBitmap) : ImageState
}

@Composable
private fun rememberImage(entryId: String?, ref: String, maxDimension: Int, referer: String?): ImageState {
    var state by remember(entryId, ref) { mutableStateOf<ImageState>(ImageState.Loading) }
    LaunchedEffect(entryId, ref, maxDimension) {
        val bitmap = AppGraph.images.bitmap(entryId, ref, maxDimension, referer)
        state = if (bitmap != null) ImageState.Ready(bitmap) else ImageState.Failed
    }
    return state
}

/**
 * Картинка из текста книги: загружается лениво, только когда блок попал на экран, и кэшируется;
 * пока грузится — заглушка, при ошибке — значок и подпись.
 */
@Composable
fun BookImage(entryId: String?, ref: String, caption: String, referer: String?, modifier: Modifier = Modifier, maxDimension: Int = 1280, maxHeight: Dp = 560.dp) {
    val state = rememberImage(entryId, ref, maxDimension, referer)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth()) {
        when (state) {
            is ImageState.Ready -> Image(
                bitmap = state.bitmap,
                contentDescription = caption.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(8.dp))
            )
            ImageState.Loading -> Placeholder(Icons.Default.Image, 160)
            ImageState.Failed -> Placeholder(Icons.Default.BrokenImage, 72)
        }
        if (caption.isNotBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp)
            )
        }
    }
}

@Composable
private fun Placeholder(icon: ImageVector, height: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = height.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = height.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Обложка книги по ссылке на ресурс либо адресу; null, пока не загружена или если её нет. */
@Composable
fun rememberCover(entryId: String?, ref: String?, maxDimension: Int = 480): ImageBitmap? {
    if (ref.isNullOrBlank()) return null
    return (rememberImage(entryId, ref, maxDimension, referer = null) as? ImageState.Ready)?.bitmap
}
