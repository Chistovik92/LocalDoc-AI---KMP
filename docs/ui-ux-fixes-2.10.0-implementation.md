# UI/UX Fixes 2.10.0 — Implementation Guide

Детальный план реализации фиксов из аудита `docs/ui-ux-audit-2.10.0.md`.

---

## Fix #1: contentDescription для смысловых иконок

**Приоритет:** Высокий  
**Файлов:** 5  
**Время:** ~1 час

### 1.1. Добавить строковые ресурсы

**Файл:** `shared/src/commonMain/composeResources/values/strings.xml`

```xml
<!-- Accessibility descriptions -->
<string name="select_book_context">Select book context</string>
<string name="download_chapter">Download chapter</string>
<string name="delete_chapter">Delete chapter</string>
<string name="ai_model_icon">AI model</string>
<string name="model_status_icon">Model status</string>
<string name="download_model">Download model</string>
<string name="update_model">Update model</string>
<string name="reload_page">Reload page</string>
<string name="browser_history">Browser history</string>
<string name="books_category">Books category</string>
<string name="articles_category">Articles category</string>
<string name="documents_category">Documents category</string>
```

**Русская версия:** `values-ru/strings.xml`

```xml
<string name="select_book_context">Выбрать контекст книги</string>
<string name="download_chapter">Скачать главу</string>
<string name="delete_chapter">Удалить главу</string>
<string name="ai_model_icon">AI-модель</string>
<string name="model_status_icon">Статус модели</string>
<string name="download_model">Скачать модель</string>
<string name="update_model">Обновить модель</string>
<string name="reload_page">Обновить страницу</string>
<string name="browser_history">История браузера</string>
<string name="books_category">Категория книг</string>
<string name="articles_category">Категория статей</string>
<string name="documents_category">Категория документов</string>
```

---

### 1.2. ChatScreen.kt

**Строка 158:**

```kotlin
// Было:
leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },

// Стало:
leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, 
    contentDescription = stringResource(Res.string.select_book_context)) },
```

**Строка 286** — оставить `null` (декоративная иконка рядом с текстом «AI Assistant»).

---

### 1.3. LibraryScreen.kt

**Строка 200** — оставить `null` (leadingIcon в search field, дублирует placeholder).

**Строка 258:**

```kotlin
// Было:
icon = { Icon(Icons.Default.Add, contentDescription = null) },

// Стало:
icon = { Icon(Icons.Default.Add, 
    contentDescription = stringResource(Res.string.add_to_the_library)) },
```

**Строка 303** — категория книг/статей/документов:

```kotlin
// Было:
leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },

// Стало:
leadingContent = { 
    Icon(icon, 
        contentDescription = when (filter.kind) {
            CatalogQuery.Kind.BOOKS -> stringResource(Res.string.books_category)
            CatalogQuery.Kind.ARTICLES -> stringResource(Res.string.articles_category)
            CatalogQuery.Kind.DOCUMENTS -> stringResource(Res.string.documents_category)
            else -> null
        },
        tint = MaterialTheme.colorScheme.primary
    )
},
```

**Строка 333** — оставить `null` (Headphones рядом с кнопкой «Слушать»).

**Строки 353, 358, 421, 426** — кнопки Download и Delete:

```kotlin
// Строка 353:
leadingIcon = { Icon(Icons.Default.Download, 
    contentDescription = stringResource(Res.string.download_chapter)) },

// Строка 358:
leadingIcon = { Icon(Icons.Default.Delete, 
    contentDescription = stringResource(Res.string.delete_chapter)) },

// Аналогично для 421 и 426
```

---

### 1.4. ModelsScreen.kt

**Строка 233:**

```kotlin
// Было:
Icon(Icons.Default.Memory, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))

// Стало:
Icon(Icons.Default.Memory, 
    contentDescription = stringResource(Res.string.ai_model_icon),
    tint = Color.White, 
    modifier = Modifier.size(36.dp))
```

**Строка 257** — статус модели:

```kotlin
// Было:
Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))

// Стало:
Icon(icon, 
    contentDescription = stringResource(Res.string.model_status_icon),
    tint = Color.White, 
    modifier = Modifier.size(14.dp))
```

**Строка 359:**

```kotlin
// Было:
Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))

// Стало:
Icon(Icons.Default.CloudDownload, 
    contentDescription = stringResource(Res.string.download_model),
    modifier = Modifier.size(18.dp))
```

**Строка 402:**

```kotlin
// Было:
Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))

// Стало:
Icon(Icons.Default.SystemUpdate, 
    contentDescription = stringResource(Res.string.update_model),
    modifier = Modifier.size(18.dp))
```

---

### 1.5. WebScreen.kt

**Строка 146:**

```kotlin
// Было:
leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },

// Стало:
leadingIcon = { Icon(Icons.Default.Refresh, 
    contentDescription = stringResource(Res.string.reload_page)) },
```

**Строка 252:**

```kotlin
// Было:
leadingContent = { Icon(Icons.Default.History, contentDescription = null) },

// Стало:
leadingContent = { Icon(Icons.Default.History, 
    contentDescription = stringResource(Res.string.browser_history)) },
```

---

### 1.6. ReaderExtras.kt

**Строка 434:**

```kotlin
// Было:
icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },

// Стало:
icon = { Icon(Icons.Default.AutoAwesome, 
    contentDescription = stringResource(Res.string.ai_assistant)) },
```

---

### 1.7. ReaderView.kt

**Строки 299, 301, 1424** — контекстные действия меню:

Эти иконки используются в динамических меню, где `action` имеет `labelRes`.  
Решение: добавить поле `contentDescriptionRes` в модель действия или использовать `labelRes` как fallback.

**Пример (строка 299):**

```kotlin
// Было:
leadingIcon = icon?.let { vector -> { Icon(vector, contentDescription = null) } },

// Стало:
leadingIcon = icon?.let { vector -> 
    { Icon(vector, contentDescription = stringResource(action.labelRes)) }
},
```

---

## Fix #2: EmptyState в поиске по тексту книги

**Приоритет:** Высокий  
**Файл:** `ContentSearchSheet.kt`  
**Время:** 20 минут

### 2.1. Добавить строковый ресурс

**Файл:** `shared/src/commonMain/composeResources/values/strings.xml`

```xml
<string name="no_results_found">No results found</string>
<string name="try_different_keywords">Try different keywords or check the spelling.</string>
```

**Русская версия:**

```xml
<string name="no_results_found">Ничего не найдено</string>
<string name="try_different_keywords">Попробуйте другие ключевые слова или проверьте правописание.</string>
```

---

### 2.2. Изменить ContentSearchSheet.kt

**Найти блок (примерно строки 66-120):**

```kotlin
LaunchedEffect(query, building) {
    if (query.isBlank()) return@LaunchedEffect
    // ... поиск
}

// Было: прямо отображается список results
LazyColumn(modifier = Modifier.fillMaxSize()) {
    items(found.hits) { hit ->
        ListItem(...)
    }
    // ...
}
```

**Заменить на:**

```kotlin
LaunchedEffect(query, building) {
    if (query.isBlank()) return@LaunchedEffect
    // ... поиск
}

// Стало: проверка на пустой результат
if (query.isNotBlank() && !building && found.hits.isEmpty() && found.catalog.isEmpty()) {
    EmptyState(
        icon = Icons.Default.SearchOff,
        title = stringResource(Res.string.no_results_found),
        text = stringResource(Res.string.try_different_keywords),
        modifier = Modifier.fillMaxSize().padding(32.dp)
    )
} else {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(found.hits, key = { "${it.entry.id}_${it.blockIndex}" }) { hit ->
            ListItem(
                modifier = Modifier.animateItemPlacement(), // Fix #3
                // ...
            )
        }
        // ...
    }
}
```

---

## Fix #3: animateItemPlacement() в списках

**Приоритет:** Высокий  
**Файлов:** 3  
**Время:** 30 минут

### 3.1. LibraryScreen.kt

**Найти `LazyColumn` (примерно строка 280):**

```kotlin
// Было:
items(shelf.entries, key = { it.id }) { entry ->
    Card(
        onClick = { onEntry(entry) },
        // ...
    ) { /* ... */ }
}

// Стало:
items(shelf.entries, key = { it.id }) { entry ->
    Card(
        onClick = { onEntry(entry) },
        modifier = Modifier.animateItemPlacement(),
        // ...
    ) { /* ... */ }
}
```

**Аналогично для всех `items()` в этом файле.**

---

### 3.2. CatalogScreen.kt

**Найти `LazyColumn` (примерно строка 240):**

```kotlin
// Было:
items(shown, key = { it.uri }) { item ->
    ListItem(
        // ...
    )
}

// Стало:
items(shown, key = { it.uri }) { item ->
    ListItem(
        modifier = Modifier.animateItemPlacement(),
        // ...
    )
}
```

---

### 3.3. ChatScreen.kt

**Найти `LazyColumn` (примерно строка 160):**

```kotlin
// Было:
itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
    val isLast = index == messages.size - 1
    ChatMessage(message, isLast)
}

// Стало:
itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
    val isLast = index == messages.size - 1
    Box(modifier = Modifier.animateItemPlacement()) {
        ChatMessage(message, isLast)
    }
}
```

**Примечание:** `Box` нужен, если `ChatMessage` сам не принимает `Modifier`.

---

## Fix #4: Haptic feedback на FAB

**Приоритет:** Высокий  
**Файл:** `LibraryScreen.kt`  
**Время:** 10 минут

### 4.1. Добавить HapticFeedback

**Найти FAB (строка 258):**

```kotlin
// Было:
FloatingActionButton(
    onClick = onAdd,
    icon = { Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_to_the_library)) },
)

// Стало:
val haptic = LocalHapticFeedback.current
FloatingActionButton(
    onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onAdd()
    },
    icon = { Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_to_the_library)) },
)
```

**Добавить импорт:**

```kotlin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
```

---

### 4.2. Аналогично для других важных действий

- **Кнопка «Удалить» в меню глав** (`LibraryScreen.kt:358, 426`)
- **Кнопка «Send» в чате** (`ChatScreen.kt`)
- **Кнопки управления озвучкой** (`ReaderView.kt`)

---

## Fix #5: Reduced motion support

**Приоритет:** Высокий  
**Файл:** `Theme.kt`  
**Время:** 40 минут

### 5.1. Добавить expect/actual для isReducedMotionEnabled()

**Файл:** `shared/src/commonMain/kotlin/com/localdoc/ai/platform/ReducedMotion.kt`

```kotlin
package com.localdoc.ai.platform

expect fun isReducedMotionEnabled(): Boolean
```

---

**Файл:** `shared/src/androidMain/kotlin/com/localdoc/ai/platform/ReducedMotion.android.kt`

```kotlin
package com.localdoc.ai.platform

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun isReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return try {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        scale == 0f
    } catch (e: Exception) {
        false
    }
}
```

---

**Файл:** `shared/src/iosMain/kotlin/com/localdoc/ai/platform/ReducedMotion.ios.kt`

```kotlin
package com.localdoc.ai.platform

import platform.UIKit.UIAccessibility

actual fun isReducedMotionEnabled(): Boolean {
    return UIAccessibility.isReduceMotionEnabled()
}
```

---

### 5.2. Использовать в Theme.kt

**Найти `MaterialExpressiveTheme` (примерно строка 170):**

```kotlin
// Было:
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = /* ... */,
        typography = /* ... */,
        shapes = /* ... */,
        content = content
    )
}

// Стало:
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val reducedMotion = isReducedMotionEnabled()
    val motionScheme = if (reducedMotion) {
        MotionScheme(
            defaultEffectsSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh
            ),
            defaultSpatialSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing)
        )
    } else {
        MotionScheme.expressive()
    }
    
    MaterialExpressiveTheme(
        colorScheme = /* ... */,
        typography = /* ... */,
        shapes = /* ... */,
        motionScheme = motionScheme,
        content = content
    )
}
```

**Добавить импорты:**

```kotlin
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
```

---

### 5.3. E-ink режим

**Проверить, что E-ink режим тоже использует reducedMotion:**

**Файл:** `shared/src/commonMain/kotlin/com/localdoc/ai/reader/ReaderSettings.kt`

Если есть флаг `eInkMode`, то в `Theme.kt`:

```kotlin
val reducedMotion = isReducedMotionEnabled() || settings.eInkMode
```

---

## Fix #6 (средний приоритет): Оптимизировать ReaderView.kt

**Приоритет:** Средний (можно отложить на 2.11.0)  
**Файл:** `ReaderView.kt`  
**Время:** 3-4 часа

### 6.1. План декомпозиции

Разбить 910+ строк на подкомпоненты:

1. **ReaderToolbar.kt** — верхняя панель (название книги, прогресс, меню)
2. **ReaderBottomPanel.kt** — нижняя панель (навигация по главам, яркость, настройки)
3. **ReaderContent.kt** — основной контент (текст книги, пагинация)
4. **ReaderGestureDetector.kt** — обработка жестов (тапы, свайпы, pinch)
5. **ReaderView.kt** — остаётся только композиция компонентов

**Цель:** Каждый компонент <250 строк, с явными параметрами и `@Stable` data class для состояния.

---

### 6.2. Пример: ReaderToolbar.kt

```kotlin
package com.localdoc.ai.ui.reader

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderToolbar(
    title: String,
    progress: Float,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
        },
        actions = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.menu))
            }
        },
        modifier = modifier
    )
    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
}
```

**Использование в ReaderView.kt:**

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    ReaderToolbar(
        title = book.title,
        progress = currentBlock.toFloat() / totalBlocks,
        onBack = onBack,
        onMenu = { menuVisible = true }
    )
    
    ReaderContent(
        blocks = blocks,
        currentBlock = currentBlock,
        onBlockChange = { currentBlock = it },
        modifier = Modifier.weight(1f)
    )
    
    ReaderBottomPanel(
        chapterNavigation = chapterNavigation,
        settings = settings,
        onSettingsChange = onSettingsChange,
        modifier = Modifier.height(80.dp)
    )
}
```

---

## Fix #7 (средний приоритет): Семантика для тап-зон

**Приоритет:** Средний (можно отложить на 2.11.0)  
**Файл:** `ReaderView.kt`  
**Время:** 1 час

### 7.1. Добавить семантику для тап-зон

**Найти `.pointerInput { detectTapGestures { ... } }` (строка 916):**

```kotlin
// Было:
Modifier
    .pointerInput(Unit) { detectTapGestures { offset -> tapNow(offset.x, size.width.toFloat()) } }

// Стало:
Modifier
    .semantics {
        contentDescription = "Reading area: tap left to go back, tap right to go forward, tap center to show menu"
        onClick {
            // Обработать клик для accessibility
            // Например, показать меню или перелистнуть на следующую страницу
            true
        }
    }
    .pointerInput(Unit) { detectTapGestures { offset -> tapNow(offset.x, size.width.toFloat()) } }
```

**Альтернатива:** Добавить невидимые кнопки для accessibility:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    // Основной контент
    Text(currentBlock.text, /* ... */)
    
    // Accessibility overlay (только для screen readers)
    Row(modifier = Modifier.fillMaxSize()) {
        // Левая зона
        Box(modifier = Modifier.weight(0.3f).fillMaxHeight()
            .clickable(
                onClick = { turnNow(false) },
                role = Role.Button,
                onClickLabel = stringResource(Res.string.previous_page)
            )
        )
        
        // Центральная зона (меню)
        Box(modifier = Modifier.weight(0.4f).fillMaxHeight()
            .clickable(
                onClick = { showMenu() },
                role = Role.Button,
                onClickLabel = stringResource(Res.string.show_menu)
            )
        )
        
        // Правая зона
        Box(modifier = Modifier.weight(0.3f).fillMaxHeight()
            .clickable(
                onClick = { turnNow(true) },
                role = Role.Button,
                onClickLabel = stringResource(Res.string.next_page)
            )
        )
    }
}
```

---

## Fix #8-10: Landscape, iOS safe areas, derivedStateOf

**Приоритет:** Средний (можно отложить на 2.11.0)  
**Время:** По 1-2 часа каждый

### 8. Landscape orientation

**Проверить все экраны на landscape:**

```bash
# Android Emulator: переключить ориентацию в настройках
# iOS Simulator: Device → Rotate Left/Right
```

**Известные проблемы landscape:**
- `ReaderView` — нижняя панель может перекрывать текст
- `ChatScreen` — клавиатура занимает половину экрана

**Решение:** Использовать `WindowInsets` и адаптивные layout'ы:

```kotlin
@Composable
fun AdaptiveReaderLayout(content: @Composable () -> Unit) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Основной контент слева
            Box(modifier = Modifier.weight(1f)) { content() }
            
            // Панели справа
            Column(modifier = Modifier.width(200.dp)) {
                ReaderToolbar(/* ... */)
                ReaderBottomPanel(/* ... */)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            ReaderToolbar(/* ... */)
            Box(modifier = Modifier.weight(1f)) { content() }
            ReaderBottomPanel(/* ... */)
        }
    }
}
```

---

### 9. iOS safe areas

**Проверить на iPhone с notch:**

```kotlin
// Добавить в App.kt:
@Composable
fun App() {
    AppTheme {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            // ...
        ) { paddingValues ->
            // Контент с учётом safe areas
            Box(modifier = Modifier.padding(paddingValues)) {
                NavHost(/* ... */)
            }
        }
    }
}
```

---

### 10. derivedStateOf для группировки библиотеки

**Файл:** `LibraryScreen.kt:162`

```kotlin
// Было:
val shelf = remember(filtered) { groupLibrary(filtered) }

// Стало:
val shelf by remember { derivedStateOf { groupLibrary(filtered) } }
```

**Преимущество:** `derivedStateOf` пересчитывает значение только при изменении `filtered`, но не вызывает рекомпозицию компонентов, которые не используют `shelf`.

---

## Чеклист тестирования после фиксов

### Manual Testing

- [ ] **TalkBack (Android):**
  - [ ] Открыть библиотеку → все иконки озвучиваются
  - [ ] FAB «Add» имеет описание «Add to the library»
  - [ ] Кнопки «Download» / «Delete» глав озвучиваются
  - [ ] Поиск по тексту книги: пустое состояние читается правильно

- [ ] **VoiceOver (iOS):**
  - [ ] Все иконки озвучиваются
  - [ ] Навигация по экранам работает правильно

- [ ] **Анимации:**
  - [ ] Добавление книги в библиотеку → плавная анимация появления
  - [ ] Удаление книги → анимация исчезновения
  - [ ] Сообщения в чате → анимация появления снизу

- [ ] **Reduced motion (Android):**
  - [ ] Settings → Accessibility → Remove animations → включить
  - [ ] Открыть LocalDoc AI → все анимации быстрые (без bounce)

- [ ] **E-ink режим:**
  - [ ] Settings → E-ink screen → включить
  - [ ] Все анимации отключены (включая ripple)

- [ ] **Haptic feedback:**
  - [ ] Нажать FAB «Add» → почувствовать вибрацию
  - [ ] Отправить сообщение в чат → вибрация

---

### Automated Testing

```bash
# Android: запустить UI tests
./gradlew :androidApp:connectedAndroidTest

# Проверить контрастность (Android Studio Accessibility Scanner)
# 1. Открыть Android Studio
# 2. Tools → Layout Inspector → Accessibility
# 3. Запустить на устройстве
# 4. Снять скриншоты всех экранов
```

---

## Оценка времени

| Fix | Приоритет | Время | Кумулятивно |
|-----|-----------|-------|-------------|
| #1 contentDescription | Высокий | 1ч | 1ч |
| #2 EmptyState search | Высокий | 20мин | 1ч 20мин |
| #3 animateItemPlacement | Высокий | 30мин | 1ч 50мин |
| #4 Haptic feedback | Высокий | 10мин | 2ч |
| #5 Reduced motion | Высокий | 40мин | 2ч 40мин |
| **Итого высокий приоритет** | | **2ч 40мин** | |
| #6 Декомпозиция ReaderView | Средний | 3-4ч | 5ч 40мин - 6ч 40мин |
| #7 Семантика тап-зон | Средний | 1ч | 6ч 40мин - 7ч 40мин |
| #8 Landscape | Средний | 2ч | 8ч 40мин - 9ч 40мин |
| #9 iOS safe areas | Средний | 1ч | 9ч 40мин - 10ч 40мин |
| #10 derivedStateOf | Средний | 30мин | 10ч 10мин - 11ч 10мин |
| **Итого всё** | | **~10-11 часов** | |

**Рекомендация:** Сделать пункты #1-5 (высокий приоритет) для версии 2.10.0, остальное отложить на 2.11.0.

---

## Следующие шаги

1. Создать ветку `feature/ui-ux-fixes-2.10.0`
2. Реализовать фиксы #1-5 (по одному коммиту на каждый)
3. Запустить ручные и автоматизированные тесты
4. Создать PR и замержить в `main`
5. Обновить `ROADMAP.md` — отметить выполненные пункты
6. Запланировать фиксы #6-10 на версию 2.11.0
