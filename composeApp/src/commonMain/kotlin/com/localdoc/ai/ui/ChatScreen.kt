package com.localdoc.ai.ui

import androidx.compose.ui.semantics.Role
import com.localdoc.ai.resources.a_short_summary_of_the_plot
import com.localdoc.ai.resources.about_the_book
import com.localdoc.ai.resources.advise_me_how_to_read_faster
import com.localdoc.ai.resources.answers_come_from_a_local_model
import com.localdoc.ai.resources.ask_about_the_book
import com.localdoc.ai.resources.ask_the_assistant
import com.localdoc.ai.resources.assistant
import com.localdoc.ai.resources.assistant_s_answer
import com.localdoc.ai.resources.characters_and_their_roles
import com.localdoc.ai.resources.clear_chat
import com.localdoc.ai.resources.explain_what_rag_is
import com.localdoc.ai.resources.facts_from_the_text
import com.localdoc.ai.resources.hello
import com.localdoc.ai.resources.help_me_with_notes
import com.localdoc.ai.resources.how_can_i_help_with_your
import com.localdoc.ai.resources.in_plain_words
import com.localdoc.ai.resources.list_the_dates_and_figures
import com.localdoc.ai.resources.make_a_reading_plan_for_the
import com.localdoc.ai.resources.model_not_loaded
import com.localdoc.ai.resources.model_not_loaded_choose_one_to
import com.localdoc.ai.resources.models
import com.localdoc.ai.resources.n_30_minutes_a_day
import com.localdoc.ai.resources.read_the_answer_aloud
import com.localdoc.ai.resources.reading_techniques
import com.localdoc.ai.resources.remove_book_context
import com.localdoc.ai.resources.send
import com.localdoc.ai.resources.stop_generating
import com.localdoc.ai.resources.structure_and_key_points
import com.localdoc.ai.resources.the_author_s_key_points
import com.localdoc.ai.resources.thinking
import com.localdoc.ai.resources.tok_s
import com.localdoc.ai.resources.what_are_the_main_ideas
import com.localdoc.ai.resources.what_is_this_book_about
import com.localdoc.ai.resources.who_are_the_main_characters
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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
    Res.string.what_is_this_book_about to Res.string.a_short_summary_of_the_plot,
    Res.string.who_are_the_main_characters to Res.string.characters_and_their_roles,
    Res.string.what_are_the_main_ideas to Res.string.the_author_s_key_points,
    Res.string.list_the_dates_and_figures to Res.string.facts_from_the_text
)
private val CHAT_SUGGESTIONS = listOf(
    Res.string.advise_me_how_to_read_faster to Res.string.reading_techniques,
    Res.string.explain_what_rag_is to Res.string.in_plain_words,
    Res.string.make_a_reading_plan_for_the to Res.string.n_30_minutes_a_day,
    Res.string.help_me_with_notes to Res.string.structure_and_key_points
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
                Text(stringResource(Res.string.assistant), style = MaterialTheme.typography.titleLarge)
                Text(
                    ModelCatalog.byId(loadedModelId.orEmpty())?.name?.let { name ->
                        if (tokensPerSec > 0f) stringResource(Res.string.tok_s, name, (tokensPerSec * 10).toInt() / 10f) else name
                    } ?: stringResource(Res.string.model_not_loaded),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (loadedModelId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = { AppGraph.clearChat() }, enabled = !isGenerating) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(Res.string.clear_chat))
                }
            }
        }
        doc?.let { current ->
            AssistChip(
                onClick = { AppGraph.currentDoc.value = null },
                label = { Text(stringResource(Res.string.about_the_book, current.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.remove_book_context)) },
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            if (doc != null) stringResource(Res.string.ask_about_the_book) else stringResource(Res.string.ask_the_assistant),
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
                    ) { Icon(Icons.Default.Stop, contentDescription = stringResource(Res.string.stop_generating)) }
                } else {
                    FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank() && loadedModelId != null) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.send))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Greeting(suggestions: List<Pair<StringResource, StringResource>>, modelLoaded: Boolean, onSuggestion: (String) -> Unit, onOpenModels: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Text(stringResource(Res.string.hello), style = gradientTextStyle(MaterialTheme.typography.displaySmall))
        Text(stringResource(Res.string.how_can_i_help_with_your), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(Res.string.answers_come_from_a_local_model),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!modelLoaded) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(
                        stringResource(Res.string.model_not_loaded_choose_one_to),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenModels) { Text(stringResource(Res.string.models)) }
                }
            }
        }
        // Две карточки в ряд на всю ширину; на широких экранах ряд не растягивается бесконечно
        FlowRow(
            maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)
        ) {
            suggestions.forEach { (titleRes, subtitleRes) ->
            val title = stringResource(titleRes)
            val subtitle = stringResource(subtitleRes)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button, enabled = modelLoaded) { onSuggestion(title) }
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
                Text(message.text.ifBlank { if (isStreaming) stringResource(Res.string.thinking) else "…" }, style = MaterialTheme.typography.bodyLarge)
            }
            if (!isStreaming && message.text.isNotBlank()) {
                val answerTitle = stringResource(Res.string.assistant_s_answer)
                IconButton(onClick = {
                    AppGraph.readAloud.start("chat", listOf(Block(BlockType.PARAGRAPH, message.text)), emptyList(), 0, answerTitle)
                }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(Res.string.read_the_answer_aloud), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
