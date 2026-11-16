# UI/UX Fixes 2.10.0 — Quick Start

Краткая инструкция для начала работы над фиксами из аудита.

---

## Порядок работы

### 1. Создать ветку

```bash
git checkout main
git pull
git checkout -b feature/ui-ux-fixes-2.10.0
```

---

### 2. Фикс #1: contentDescription (1 час)

#### 2.1. Добавить строки в ресурсы

**Файл:** `shared/src/commonMain/composeResources/values/strings.xml`

Добавить в конец файла (перед `</resources>`):

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
<string name="books_category">Books</string>
<string name="articles_category">Articles</string>
<string name="documents_category">Documents</string>
```

**Файл:** `shared/src/commonMain/composeResources/values-ru/strings.xml`

```xml
<!-- Accessibility descriptions -->
<string name="select_book_context">Выбрать контекст книги</string>
<string name="download_chapter">Скачать главу</string>
<string name="delete_chapter">Удалить главу</string>
<string name="ai_model_icon">AI-модель</string>
<string name="model_status_icon">Статус модели</string>
<string name="download_model">Скачать модель</string>
<string name="update_model">Обновить модель</string>
<string name="reload_page">Обновить страницу</string>
<string name="browser_history">История</string>
<string name="books_category">Книги</string>
<string name="articles_category">Статьи</string>
<string name="documents_category">Документы</string>
```

#### 2.2. Исправить файлы (по одному коммиту на файл)

**ChatScreen.kt** → коммит `a11y: Add contentDescription to ChatScreen icons`  
**LibraryScreen.kt** → коммит `a11y: Add contentDescription to LibraryScreen icons`  
**ModelsScreen.kt** → коммит `a11y: Add contentDescription to ModelsScreen icons`  
**WebScreen.kt** → коммит `a11y: Add contentDescription to WebScreen icons`  
**ReaderExtras.kt** → коммит `a11y: Add contentDescription to ReaderExtras icons`

**Детали изменений:** см. `docs/ui-ux-fixes-2.10.0-implementation.md` → раздел Fix #1

---

### 3. Фикс #2: EmptyState в поиске (20 минут)

#### 3.1. Добавить строки

```xml
<!-- values/strings.xml -->
<string name="no_results_found">No results found</string>
<string name="try_different_keywords">Try different keywords or check the spelling.</string>

<!-- values-ru/strings.xml -->
<string name="no_results_found">Ничего не найдено</string>
<string name="try_different_keywords">Попробуйте другие ключевые слова или проверьте правописание.</string>
```

#### 3.2. Исправить ContentSearchSheet.kt

Найти блок с `LazyColumn` и обернуть в проверку на пустой результат:

```kotlin
if (query.isNotBlank() && !building && found.hits.isEmpty() && found.catalog.isEmpty()) {
    EmptyState(
        icon = Icons.Default.SearchOff,
        title = stringResource(Res.string.no_results_found),
        text = stringResource(Res.string.try_different_keywords),
        modifier = Modifier.fillMaxSize().padding(32.dp)
    )
} else {
    LazyColumn(modifier = Modifier.fillMaxSize()) { /* ... */ }
}
```

**Коммит:** `ux: Add EmptyState to content search`

---

### 4. Фикс #3: animateItemPlacement() (30 минут)

Добавить `.animateItemPlacement()` в `items()` трёх файлов:

**LibraryScreen.kt:**

```kotlin
items(shelf.entries, key = { it.id }) { entry ->
    Card(
        onClick = { onEntry(entry) },
        modifier = Modifier.animateItemPlacement(), // <-- добавить
        // ...
    )
}
```

**CatalogScreen.kt:**

```kotlin
items(shown, key = { it.uri }) { item ->
    ListItem(
        modifier = Modifier.animateItemPlacement(), // <-- добавить
        // ...
    )
}
```

**ChatScreen.kt:**

```kotlin
itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
    Box(modifier = Modifier.animateItemPlacement()) { // <-- обернуть в Box
        ChatMessage(message, isLast = index == messages.size - 1)
    }
}
```

**Коммиты (по одному на файл):**
- `animation: Add animateItemPlacement to LibraryScreen`
- `animation: Add animateItemPlacement to CatalogScreen`
- `animation: Add animateItemPlacement to ChatScreen`

---

### 5. Фикс #4: Haptic feedback (10 минут)

**LibraryScreen.kt**, найти FAB (строка ~258):

```kotlin
val haptic = LocalHapticFeedback.current // <-- добавить перед FAB
FloatingActionButton(
    onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress) // <-- добавить
        onAdd()
    },
    icon = { Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_to_the_library)) },
)
```

**Импорты:**

```kotlin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
```

**Коммит:** `ux: Add haptic feedback to FAB in LibraryScreen`

---

### 6. Фикс #5: Reduced motion (40 минут)

#### 6.1. Создать expect/actual

**Файл:** `shared/src/commonMain/kotlin/com/localdoc/ai/platform/ReducedMotion.kt`

```kotlin
package com.localdoc.ai.platform

import androidx.compose.runtime.Composable

@Composable
expect fun isReducedMotionEnabled(): Boolean
```

**Файл:** `shared/src/androidMain/kotlin/com/localdoc/ai/platform/ReducedMotion.android.kt`

```kotlin
package com.localdoc.ai.platform

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

**Файл:** `shared/src/iosMain/kotlin/com/localdoc/ai/platform/ReducedMotion.ios.kt`

```kotlin
package com.localdoc.ai.platform

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibility

@Composable
actual fun isReducedMotionEnabled(): Boolean {
    return UIAccessibility.isReduceMotionEnabled()
}
```

#### 6.2. Использовать в Theme.kt

Найти `MaterialExpressiveTheme` и добавить:

```kotlin
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
    motionScheme = motionScheme, // <-- добавить
    // ...
)
```

**Импорты:**

```kotlin
import androidx.compose.animation.core.*
```

**Коммит:** `a11y: Add reduced motion support for accessibility`

---

## Проверка работы

### После каждого коммита:

```bash
# Android: собрать
./gradlew :androidApp:assembleDebug

# Запустить на эмуляторе
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Проверить:
# - Фикс #1: включить TalkBack → проверить озвучку иконок
# - Фикс #2: искать несуществующую фразу → видеть EmptyState
# - Фикс #3: добавить/удалить книгу → видеть плавную анимацию
# - Фикс #4: нажать FAB → почувствовать вибрацию
# - Фикс #5: Settings → Accessibility → Remove animations → проверить быстрые анимации
```

---

## Финальный чек перед PR

```bash
# 1. Убедиться, что все тесты проходят
./gradlew :shared:testDebugUnitTest
./gradlew :androidApp:testDebugUnitTest

# 2. Проверить сборку Android
./gradlew :androidApp:assembleDebug

# 3. (На macOS) Проверить сборку iOS
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

# 4. Проверить git status
git status  # Должно быть ~10 изменённых файлов

# 5. Запушить ветку
git push -u origin feature/ui-ux-fixes-2.10.0

# 6. Создать PR на GitHub
# Заголовок: UI/UX fixes for 2.10.0 (accessibility, animations, reduced motion)
# Описание: ссылка на docs/ui-ux-audit-2.10.0.md
```

---

## Коммиты должны быть:

```
a11y: Add contentDescription to ChatScreen icons
a11y: Add contentDescription to LibraryScreen icons
a11y: Add contentDescription to ModelsScreen icons
a11y: Add contentDescription to WebScreen icons
a11y: Add contentDescription to ReaderExtras icons
ux: Add EmptyState to content search
animation: Add animateItemPlacement to LibraryScreen
animation: Add animateItemPlacement to CatalogScreen
animation: Add animateItemPlacement to ChatScreen
ux: Add haptic feedback to FAB in LibraryScreen
a11y: Add reduced motion support for accessibility
```

**Итого:** 11 коммитов, ~2ч 40мин работы.

---

## Если что-то пошло не так

### Сборка не проходит

```bash
# Чистая пересборка
./gradlew clean
./gradlew :androidApp:assembleDebug
```

### TalkBack не озвучивает иконки

- Проверить, что строка добавлена в `Res.string.*`
- Проверить, что `contentDescription = stringResource(Res.string.*)`, а не `null`
- Перезапустить TalkBack: Settings → Accessibility → TalkBack → выключить и включить

### Анимации не работают

- Проверить, что добавлен `.animateItemPlacement()`
- Проверить, что `key = { it.id }` уникален для каждого элемента
- Выключить E-ink режим в настройках приложения

### Reduced motion не работает на iOS

- Проверить, что файл `ReducedMotion.ios.kt` находится в `iosMain/kotlin/`
- Проверить импорт: `platform.UIKit.UIAccessibility`
- На iOS Simulator: Settings → Accessibility → Motion → Reduce Motion → включить

---

## После мерджа в main

1. Обновить `ROADMAP.md` — отметить выполненные пункты ✅
2. Обновить `CHANGELOG.md` — добавить раздел для 2.10.0
3. Удалить ветку:

```bash
git checkout main
git pull
git branch -D feature/ui-ux-fixes-2.10.0
git push origin --delete feature/ui-ux-fixes-2.10.0
```

---

## Контакты для вопросов

- **Полный аудит:** `docs/ui-ux-audit-2.10.0.md`
- **Детальная инструкция:** `docs/ui-ux-fixes-2.10.0-implementation.md`
- **Roadmap:** `ROADMAP.md`
