package com.localdoc.ai.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.SmartPipeline
import com.localdoc.ai.lang.LanguageDetector
import com.localdoc.ai.lang.Languages
import com.localdoc.ai.notes.AnnotationColor
import com.localdoc.ai.notes.WordLinks
import com.localdoc.ai.platform.PlatformActions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Что можно сделать с выделенным в читалке текстом. */
internal enum class SelectionAction(val label: String) {
    QUOTE("Цитата"),
    NOTE("Заметка"),
    WORD("Слово"),
    /** Отметить выделенное («Глава 5», «Пролог») как начало главы. */
    CHAPTER("Глава"),
    COPY("Копировать")
}

/**
 * Вместо системного меню выделения (оно не сообщает, какой текст выделен) читалка показывает свою панель
 * действий: когда выделение появляется, в [copy] лежит функция, копирующая выделенное в буфер обмена.
 */
internal class SelectionToolbarState {
    var copy by mutableStateOf<(() -> Unit)?>(null)
    val visible: Boolean get() = copy != null
}

internal class ReaderTextToolbar(private val state: SelectionToolbarState) : TextToolbar {
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        state.copy = onCopyRequested
        status = if (onCopyRequested != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
    }

    override fun hide() {
        state.copy = null
        status = TextToolbarStatus.Hidden
    }
}

/**
 * Текст выделения: копирует его в буфер обмена и читает оттуда. Копирование в Compose может завершиться не
 * сразу, поэтому несколько коротких попыток. Если [keepInClipboard] == false, прежнее содержимое буфера возвращается.
 */
@Suppress("DEPRECATION")
internal suspend fun readSelection(clipboard: ClipboardManager, copy: () -> Unit, keepInClipboard: Boolean): String? {
    val previous = clipboard.getText()
    clipboard.setText(AnnotatedString(SENTINEL))
    copy()
    var selected: String? = null
    for (attempt in 0 until COPY_ATTEMPTS) {
        delay(COPY_WAIT_MS)
        val now = clipboard.getText()?.text
        if (now != null && now != SENTINEL) {
            selected = now
            break
        }
    }
    if (!keepInClipboard || selected == null) previous?.let(clipboard::setText)
    return selected?.takeIf { it.isNotBlank() }
}

private const val SENTINEL = "\u0000localdoc-selection\u0000"
private const val COPY_ATTEMPTS = 5
private const val COPY_WAIT_MS = 40L

/** Панель действий над выделенным текстом. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionActionBar(
    canAnnotate: Boolean,
    onAction: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
    canMarkChapter: Boolean = false
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            SelectionAction.entries.forEach { action ->
                if (!canAnnotate && (action == SelectionAction.QUOTE || action == SelectionAction.NOTE)) return@forEach
                if (!canMarkChapter && action == SelectionAction.CHAPTER) return@forEach
                TextButton(onClick = { onAction(action) }) {
                    Text(action.label, color = MaterialTheme.colorScheme.inversePrimary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Короткое сообщение внизу читалки («Цитата сохранена»); пропадает само. */
@Composable
internal fun ReaderNotice(text: String?, onGone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) {
        if (text != null) {
            delay(NOTICE_MS)
            onGone()
        }
    }
    if (text == null) return
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

private const val NOTICE_MS = 2_500L

/** Диалог заметки к цитате: показывает цитату и поле для текста (при правке поле заполнено прежней заметкой). */
@Composable
internal fun NoteDialog(
    quote: String,
    initial: String,
    initialColor: AnnotationColor,
    onDismiss: () -> Unit,
    onSave: (String, AnnotationColor) -> Unit,
    /** Только для уже сохранённой цитаты: убрать её вместе с подсветкой. */
    onDelete: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf(initial) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isBlank()) "Заметка" else "Правка заметки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorPicker(color) { color = it }
                Text(
                    quote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Ваши мысли об этом месте") },
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, color) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

/** Выбор цвета подсветки цитаты. */
@Composable
private fun ColorPicker(selected: AnnotationColor, onSelect: (AnnotationColor) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AnnotationColor.entries.forEach { color ->
            val chosen = color == selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color(color.argb).copy(alpha = 1f), CircleShape)
                    .border(
                        BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        CircleShape
                    )
                    .semantics { contentDescription = color.label }
                    .clickable { onSelect(color) }
            )
        }
    }
}

/**
 * Слово из текста: модель переводит его в контексте предложения, пользователь правит значение и сохраняет в
 * личный словарь. Без загруженной модели значение вводится вручную.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WordLookupSheet(
    word: String,
    sentence: String,
    entryId: String,
    bookTitle: String,
    sourceLanguage: String?,
    onOpenModels: () -> Unit,
    onDismiss: () -> Unit
) {
    val translation by AppGraph.translationSettings.value.collectAsState()
    val loadedModel by AppGraph.loadedModelId.collectAsState()
    val target = Languages.byCode(translation.targetLanguage)
    val saved = remember(word) { AppGraph.dictionary.find(word) }
    var meaning by remember(word) { mutableStateOf(saved?.meaning.orEmpty()) }
    var loading by remember(word) { mutableStateOf(false) }
    var error by remember(word) { mutableStateOf<String?>(null) }
    // 0 — спросить модель при открытии, -1 — слово уже в словаре, повторный запрос только по кнопке
    var attempt by remember(word) { mutableStateOf(if (saved == null) 0 else -1) }
    val modelReady = loadedModel != null

    LaunchedEffect(word, attempt, modelReady) {
        if (attempt < 0 || target == null || !modelReady) return@LaunchedEffect
        loading = true
        error = null
        AppGraph.translator.hold()
        try {
            withTimeoutOrNull(WAIT_FOR_TRANSLATION_MS) { AppGraph.translator.awaitIdle() }
            val answer = StringBuilder()
            AppGraph.pipeline.lookup(word, sentence, target, Languages.byCode(sourceLanguage)).collect {
                answer.append(it)
                meaning = SmartPipeline.stripThinking(answer.toString()).trim()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось получить значение"
        } finally {
            AppGraph.translator.release()
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)
        ) {
            Text(word, style = MaterialTheme.typography.headlineSmall)
            Text(sentence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(
                value = meaning,
                onValueChange = { meaning = it },
                label = { Text(if (target != null) "Перевод: ${target.name}" else "Значение") },
                supportingText = {
                    Text(
                        when {
                            loading -> "Модель подбирает значение…"
                            error != null -> error.orEmpty()
                            !modelReady && saved == null -> "Модель не загружена — введите значение сами или загрузите модель"
                            else -> ""
                        }
                    )
                },
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = meaning.isNotBlank() && !loading,
                    onClick = {
                        AppGraph.dictionary.save(word, meaning, sentence, entryId, bookTitle, target?.code.orEmpty())
                        onDismiss()
                    }
                ) { Text(if (saved != null) "Обновить в словаре" else "В словарь", maxLines = 1, softWrap = false) }
                if (modelReady) {
                    TextButton(enabled = !loading, onClick = { attempt = maxOf(attempt, 0) + 1 }) {
                        Text("Спросить модель", maxLines = 1, softWrap = false)
                    }
                } else {
                    TextButton(onClick = { onDismiss(); onOpenModels() }) { Text("Загрузить модель", maxLines = 1, softWrap = false) }
                }
                // Википедия — на языке слова: язык оригинала книги, иначе определяется по предложению
                val wordLanguage = sourceLanguage ?: LanguageDetector.detect(sentence.ifBlank { word })
                TextButton(onClick = { PlatformActions.openUrl(WordLinks.webSearch(word)) }) {
                    Text("Найти в интернете", maxLines = 1, softWrap = false)
                }
                TextButton(onClick = { PlatformActions.openUrl(WordLinks.wikipedia(word, wordLanguage)) }) {
                    Text("Википедия", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private const val WAIT_FOR_TRANSLATION_MS = 60_000L
