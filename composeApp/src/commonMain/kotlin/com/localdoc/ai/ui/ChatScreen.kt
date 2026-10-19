package com.localdoc.ai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage

private val DOC_SUGGESTIONS = listOf(
    "О чём этот документ?",
    "Какие главные выводы?",
    "Перечисли все даты и суммы"
)
private val CHAT_SUGGESTIONS = listOf(
    "Объясни, что такое RAG",
    "Составь план на неделю",
    "Помоги сформулировать письмо"
)

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
        if (question.isEmpty() || AppGraph.isGenerating.value) return
        input = ""
        AppGraph.addMessage(ChatMessage(ChatMessage.Role.USER, question))
        val currentDoc = AppGraph.currentDoc.value
        launchGeneration(
            if (currentDoc != null) AppGraph.pipeline.ask(currentDoc, question) else AppGraph.pipeline.chat(question)
        )
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).imePadding()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            StatChip("Документ", doc?.title?.take(22) ?: "нет")
            if (tokensPerSec > 0f) StatChip("Скорость", "${(tokensPerSec * 10).toInt() / 10f} ток/с")
            Spacer(Modifier.weight(1f))
            if (messages.isNotEmpty()) {
                IconButton(onClick = { AppGraph.clearChat() }, enabled = !isGenerating) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить чат")
                }
            }
        }

        if (loadedModelId == null) {
            NoModelBanner(onOpenModels)
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyChat(
                        suggestions = if (doc != null) DOC_SUGGESTIONS else CHAT_SUGGESTIONS,
                        enabled = loadedModelId != null,
                        onSuggestion = ::send
                    )
                }
            }
            items(messages) { message ->
                val isStreaming = isGenerating && message === messages.last()
                MessageBubble(message, isStreaming)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (doc != null) "Вопрос по документу…" else "Сообщение…") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RoundedCornerShape(24.dp)
            )
            AnimatedContent(targetState = isGenerating, label = "send-stop") { generating ->
                if (generating) {
                    FilledIconButton(
                        onClick = { stopGeneration() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Остановить генерацию")
                    }
                } else {
                    FilledIconButton(
                        onClick = { send(input) },
                        enabled = input.isNotBlank() && loadedModelId != null,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}

@Composable
private fun NoModelBanner(onOpenModels: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(suggestions: List<String>, enabled: Boolean, onSuggestion: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp)
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(16.dp).size(32.dp)
            )
        }
        Text("Чем помочь?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Ответы генерируются локальной моделью — данные не покидают устройство.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            suggestions.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestion(suggestion) },
                    enabled = enabled,
                    label = { Text(suggestion) }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isStreaming: Boolean) {
    val isUser = message.role == ChatMessage.Role.USER
    Box(
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            modifier = Modifier.widthIn(max = 560.dp).padding(start = if (isUser) 40.dp else 0.dp, end = if (isUser) 0.dp else 24.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!isUser) {
                    Text(
                        "LocalDoc AI",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                val text = when {
                    message.text.isNotBlank() -> message.text
                    isStreaming -> "Думаю…"
                    else -> "…"
                }
                // Ответ можно выделить и скопировать системными средствами
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
