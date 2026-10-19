package com.localdoc.ai.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.ui.Modifier as M
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.IncomingDoc
import com.localdoc.ai.parser.ParsedDoc
import kotlinx.coroutines.launch

private enum class Screen(val label: String, val icon: ImageVector) {
    Models("Модели", Icons.Default.Memory),
    Library("Документ", Icons.Default.Description),
    Chat("Чат", Icons.Default.Create)
}

@Composable
fun App() {
    LocalDocTheme {
        var screen by remember { mutableStateOf(Screen.Models) }

        // Обработка входящих документов (Share Intent на Android, файлы)
        val incoming by AppGraph.incoming.collectAsState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var processingTitle by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(incoming) {
            val item = incoming ?: return@LaunchedEffect
            AppGraph.acceptIncoming(null)
            processingTitle = when (item.type) {
                IncomingDoc.IncomingType.URL -> item.payload
                IncomingDoc.IncomingType.PDF -> item.payload.substringAfterLast('/')
                IncomingDoc.IncomingType.TEXT -> "Текст из буфера"
            }
            scope.launch {
                try {
                    val doc: ParsedDoc = when (item.type) {
                        IncomingDoc.IncomingType.URL -> AppGraph.webParser.parseUrl(item.payload)
                        IncomingDoc.IncomingType.TEXT -> ParsedDoc(
                            title = "Присланный текст",
                            content = item.payload,
                            source = "Share Intent"
                        )
                        IncomingDoc.IncomingType.PDF -> AppGraph.pdfExtractor.extract(item.payload)
                    }
                    AppGraph.currentDoc.value = doc
                } catch (t: Throwable) {
                    AppGraph.messages.value = AppGraph.messages.value + com.localdoc.ai.domain.ChatMessage(
                        com.localdoc.ai.domain.ChatMessage.Role.ASSISTANT,
                        "Не удалось обработать источник: ${t.message}"
                    )
                } finally {
                    processingTitle = null
                }
            }
        }

        Scaffold(
            snackbarHost = { },
            bottomBar = {
                NavigationBar {
                    Screen.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = screen == entry,
                            onClick = { screen = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) }
                        )
                    }
                }
            }
        ) { padding ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                when (screen) {
                    Screen.Models -> ModelsScreen()
                    Screen.Library -> LibraryScreen(onOpenChat = { screen = Screen.Chat })
                    Screen.Chat -> ChatScreen()
                }
                ProcessingOverlay(processingTitle)
            }
        }
    }
}
