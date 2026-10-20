package com.localdoc.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage
import com.localdoc.ai.model.ModelCatalog

private val DOC_SUGGESTIONS = listOf(
    "О чём эта книга?" to "Короткий пересказ сюжета",
    "Кто главные герои?" to "Персонажи и их роли",
    "Какие главные идеи?" to "Ключевые мысли автора",
    "Перечисли даты и цифры" to "Факты из текста"
)
private val CHAT_SUGGESTIONS = listOf(
    "Посоветуй, как читать быстрее" to "Техники чтения",
    "Объясни, что такое RAG" to "Простыми словами",
    "Составь план чтения на неделю" to "По 30 минут в день",
    "Помоги с конспектом" to "Структура и тезисы"
)

/** Вкладка «Ассистент» в стиле Gemini: вопросы по открытой книге или свободный чат. */
@Composable
fun ChatScreen(onOpenModels: () -> Unit) {
    val messages by AppGraph.messages.collectAsState()
    val isGenerating by AppGraph.isGenerating.collectAsState()
    val tokensPerSec by AppGraph.statsTokensPerSec.collectAsState()
    val loadedModelId by AppGraph.loadedModelId.collectAsState()
    val doc by AppGraph.currentDoc.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || AppGraph.isGenerating.value || AppGraph.loadedModelId.value == null) return
        input = ""
        AppGraph.addMessage(ChatMessage(ChatMessage.Role.USER, question))
        val currentDoc = AppGraph.currentDoc.value
        launchGeneration(if (currentDoc != null) AppGraph.pipeline.ask(currentDoc, question) else AppGraph.pipeline.chat(question))
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Ассистент", style = MaterialTheme.typography.titleLarge)
                Text(
                    ModelCatalog.byId(loadedModelId.orEmpty())?.name?.let { name ->
                        if (tokensPerSec > 0f) "$name · ${(tokensPerSec * 10).toInt() / 10f} ток/с" else name
                    } ?: "Модель не загружена",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (loadedModelId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = { AppGraph.clearChat() }, enabled = !isGenerating) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить чат")
                }
            }
        }
        doc?.let { current ->
            AssistChip(
                onClick = { AppGraph.currentDoc.value = null },
                label = { Text("По книге: ${current.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Убрать контекст книги") },
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp)
            )
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Greeting(
                        suggestions = if (doc != null) DOC_SUGGESTIONS else CHAT_SUGGESTIONS,
                        modelLoaded = loadedModelId != null,
                        onSuggestion = ::send,
                        onOpenModels = onOpenModels
                    )
                }
            }
            items(messages) { message ->
                MessageItem(message, isStreaming = isGenerating && message === messages.last())
            }
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            if (doc != null) "Спросите о книге" else "Спросите ассистента",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (isGenerating) {
                    FilledIconButton(
                        onClick = { stopGeneration() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Icon(Icons.Default.Stop, contentDescription = "Остановить генерацию") }
                } else {
                    FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank() && loadedModelId != null) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Greeting(suggestions: List<Pair<String, String>>, modelLoaded: Boolean, onSuggestion: (String) -> Unit, onOpenModels: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Text("Привет!", style = gradientTextStyle(MaterialTheme.typography.displaySmall))
        Text("Чем помочь с чтением?", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Ответы генерирует локальная модель — текст книг не покидает устройство.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!modelLoaded) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(
                        "Модель не загружена — выберите её, чтобы начать.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenModels) { Text("Модели") }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            suggestions.forEach { (title, subtitle) ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.width(168.dp).clickable(enabled = modelLoaded) { onSuggestion(title) }
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage, isStreaming: Boolean) {
    if (message.role == ChatMessage.Role.USER) {
        Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                modifier = Modifier.widthIn(max = 520.dp).padding(start = 48.dp)
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoogleColors.Blue, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SelectionContainer {
                Text(message.text.ifBlank { if (isStreaming) "Думаю…" else "…" }, style = MaterialTheme.typography.bodyLarge)
            }
            if (!isStreaming && message.text.isNotBlank()) {
                IconButton(onClick = {
                    AppGraph.readAloud.start("chat", listOf(Block(BlockType.PARAGRAPH, message.text)), emptyList(), 0, "Ответ ассистента")
                }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Озвучить ответ", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
