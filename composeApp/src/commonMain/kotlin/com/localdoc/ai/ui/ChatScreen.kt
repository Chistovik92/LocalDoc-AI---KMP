package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage
import kotlinx.coroutines.launch

@Composable
fun ChatScreen() {
    val scope = rememberCoroutineScope()
    val messages by AppGraph.messages.collectAsState()
    val isGenerating by AppGraph.isGenerating.collectAsState()
    val tokensPerSec by AppGraph.statsTokensPerSec.collectAsState()
    val doc by AppGraph.currentDoc.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp).imePadding()
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Скорость", String.format("%.1f ток/с", tokensPerSec))
            StatChip("Документ", doc?.title?.take(24) ?: "нет")
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Задайте вопрос по документу или просто спросите что-нибудь. " +
                            "Ответ полностью генерируется локальной моделью на устройстве.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (doc != null) "Вопрос по документу…" else "Ваш вопрос…") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            if (isGenerating) {
                Button(
                    onClick = { AppGraph.engine.stop() },
                    modifier = Modifier.padding(top = 6.dp)
                ) { Text("Стоп") }
            } else {
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty()) return@Button
                        input = ""
                        AppGraph.addMessage(ChatMessage(ChatMessage.Role.USER, text))
                        val currentDoc = AppGraph.currentDoc.value
                        val flow = if (currentDoc != null) {
                            AppGraph.pipeline.ask(currentDoc, text)
                        } else {
                            AppGraph.pipeline.chat(text)
                        }
                        launchGeneration(scope, flow)
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.padding(top = 6.dp)
                ) { Text("→") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 1f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Text(
                message.text.ifBlank { "…" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
