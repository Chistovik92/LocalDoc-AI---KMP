package com.localdoc.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** Поле-«пилюля» в стиле поиска Google: поиск по библиотеке, адресная строка. */
@Composable
fun GooglePillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Search,
    onSubmit: () -> Unit = {},
    trailing: @Composable () -> Unit = {}
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 56.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp)
        ) {
            Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            trailing()
        }
    }
}

/** Пара цветов обложки (фон / текст) в духе Google Play Книг. */
private val COVER_PALETTE = listOf(
    Color(0xFFD3E3FD) to Color(0xFF041E49),
    Color(0xFFF9DEDC) to Color(0xFF410E0B),
    Color(0xFFFFEFC2) to Color(0xFF3D2E00),
    Color(0xFFC4EED0) to Color(0xFF072711),
    Color(0xFFE8DEF8) to Color(0xFF1D192B),
    Color(0xFFC2E7FF) to Color(0xFF001D35)
)

/** Сгенерированная обложка: цвет по названию, крупная буквица, автор и формат. */
@Composable
fun BookCover(title: String, author: String, format: BookFormat, modifier: Modifier = Modifier) {
    val (bg, fg) = COVER_PALETTE[(title.hashCode() and 0x7FFFFFFF) % COVER_PALETTE.size]
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.aspectRatio(0.68f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title.trim().firstOrNull()?.uppercase() ?: "•",
                color = fg.copy(alpha = 0.85f),
                fontFamily = FontFamily.Serif,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(title, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (author.isNotBlank()) {
                Text(author, color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Surface(color = fg.copy(alpha = 0.12f), shape = CircleShape) {
                Text(format.label, color = fg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

/** Пустое состояние с иконкой в тональном круге. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        actions()
    }
}

/** Модальный индикатор долгой операции (импорт, OCR, загрузка страницы). */
@Composable
fun ProcessingDialog(title: String?, subtitle: String = "Извлечение текста (при необходимости — локальный OCR)…") {
    if (title == null) return
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(24.dp).width(280.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Диалог ввода строки. */
@Composable
fun TextInputDialog(
    visible: Boolean,
    title: String,
    placeholder: String,
    confirmLabel: String,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (!visible) return
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 8,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** Пять точек-оценок (качество, скорость, русский). */
@Composable
fun RatingDots(value: Int, color: Color, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = modifier) {
        repeat(5) { i ->
            Box(Modifier.size(8.dp).background(if (i < value) color else color.copy(alpha = 0.18f), CircleShape))
        }
    }
}

/** Запуск генерации: добавляет ответ ассистента, стримит токены, считает tokens/sec. */
fun launchGeneration(flow: kotlinx.coroutines.flow.Flow<String>) {
    if (AppGraph.isGenerating.value) return
    AppGraph.isGenerating.value = true
    AppGraph.statsTokensPerSec.value = 0f
    AppGraph.addMessage(ChatMessage(ChatMessage.Role.ASSISTANT, ""))

    val previous = AppGraph.generationJob
    AppGraph.generationJob = AppGraph.appScope.launch {
        // Остановленная генерация может ещё дорабатывать токен в C-ядре — дожидаемся её
        previous?.join()
        val clock = TimeSource.Monotonic.markNow()
        var tokenCount = 0
        val acc = StringBuilder()

        try {
            flow.collect { token ->
                acc.append(token)
                tokenCount++
                AppGraph.updateLastAssistant(acc.toString())
                val seconds = clock.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) / 1000f
                AppGraph.statsTokensPerSec.value = tokenCount / seconds
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppGraph.updateLastAssistant(acc.toString().ifBlank { "Генерация остановлена." })
            throw e
        } catch (t: Throwable) {
            AppGraph.updateLastAssistant(acc.toString().ifBlank { "Ошибка генерации: ${t.message}" })
        } finally {
            AppGraph.isGenerating.value = false
            if (AppGraph.generationJob === coroutineContext[Job]) AppGraph.generationJob = null
        }
    }
}

/** Остановка генерации: прерывает C-ядро и отменяет сбор потока. */
fun stopGeneration() {
    AppGraph.engine.stop()
    AppGraph.generationJob?.cancel()
}

/** Размер модели/файла в человекочитаемом виде. */
fun formatSize(sizeMb: Long): String =
    if (sizeMb >= 1024) "${(sizeMb * 10 / 1024) / 10.0} ГБ" else "$sizeMb МБ"
