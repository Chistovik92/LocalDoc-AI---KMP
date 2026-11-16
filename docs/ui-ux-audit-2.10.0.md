# UI/UX Audit — LocalDoc AI 2.10.0

**Дата:** 2026-11-16  
**Цель:** Комплексный аудит UI/UX кода перед релизом версии 2.10.0  
**Области:** Доступность (a11y), отзывчивость, Material 3 Expressive, UX-паттерны, производительность

---

## 1. Доступность (Accessibility)

### 1.1. Проблема: `contentDescription = null` (27 экземпляров)

**Найдено:** 27 иконок без описания для screen readers.

**Критичность:** 🟡 Средняя — часть иконок декоративные (дублируют текст), часть — действия без подписей.

#### Декоративные (можно оставить `null` или удалить параметр):

1. **BookImage.kt:92** — иконка формата книги рядом с названием  
2. **Common.kt:91** — leadingIcon в `GooglePillField` (дублирует placeholder)  
3. **Common.kt:188** — иконка в `EmptyState` (дублирует title)  
4. **ChatScreen.kt:286** — `AutoAwesome` рядом с текстом «AI Assistant»  
5. **LibraryScreen.kt:200** — `Search` в строке поиска (дублирует placeholder)  
6. **LibraryScreen.kt:333** — `Headphones` рядом с кнопкой «Слушать»  
7. **SettingsScreen.kt:186** — иконка раздела настроек (дублирует заголовок)  
8. **SettingsScreen.kt:199** — `KeyboardArrowRight` в списке (стандартный навигационный индикатор)  
9. **ReaderSheets.kt:680** — `AutoAwesome` в меню AI (рядом с текстом)  
10. **CatalogScreen.kt:339** — `Folder` рядом с названием полки  

#### Требуют описания (смысловые действия):

11. **ChatScreen.kt:158** — `MenuBook` — leadingIcon в выборе контекста книги  
    → `contentDescription = stringResource(Res.string.select_book_context)`

12. **LibraryScreen.kt:258** — FAB `Add` — главная кнопка добавления книги  
    → `contentDescription = stringResource(Res.string.add_to_the_library)`

13. **LibraryScreen.kt:303** — иконка типа книги (books/articles/documents)  
    → `contentDescription = stringResource(filter.icon.contentDescription)`  
    *(нужны ресурсы: `books_category`, `articles_category`, `documents_category`)*

14. **LibraryScreen.kt:353, 358, 421, 426** — `Download` и `Delete` в меню глав  
    → `contentDescription = stringResource(Res.string.download_chapter)` / `delete_chapter`

15. **ModelsScreen.kt:233, 257** — иконки `Memory` и статуса модели  
    → `contentDescription = stringResource(Res.string.ai_model_icon)` / `model.status.label`

16. **ModelsScreen.kt:359, 402** — `CloudDownload` и `SystemUpdate`  
    → `contentDescription = stringResource(Res.string.download_model)` / `update_model`

17. **WebScreen.kt:146, 252** — `Refresh` и `History`  
    → `contentDescription = stringResource(Res.string.reload_page)` / `browser_history`

18. **ReaderExtras.kt:434** — `AutoAwesome` в FAB AI-меню  
    → `contentDescription = stringResource(Res.string.ai_assistant)`

19. **ReaderView.kt:299, 301, 1424** — иконки в меню чтения (галочки, действия)  
    → Контекстно: `contentDescription = stringResource(action.labelRes)`

---

### 1.2. Семантика и роли

✅ **Хорошо:**  
- Кнопки имеют `Role.Button` через `IconButton` / `Button` / `FloatingActionButton`  
- Toggleable элементы явно используют `Modifier.toggleable(role = Role.Checkbox)`  
- `ListItem` используются корректно для списков настроек

⚠️ **Улучшить:**  
- **ReaderView.kt:916** — `.pointerInput { detectTapGestures { ... } }` на всём экране чтения  
  → Тап-зоны не экспонируются для accessibility. Добавить семантику:
  ```kotlin
  .semantics {
      contentDescription = "Tap left to turn page back, tap right to turn page forward"
      onClick { /* handle tap */ }
  }
  ```

---

### 1.3. Контрастность цветов

✅ **Хорошо:**  
- Material 3 динамические цвета обеспечивают контраст 4.5:1 на Light/Dark темах  
- Режимы «Чёрный», «Сепия», «Высокий контраст» для читателя — отличная практика  

🔍 **Проверить:**  
- **SemanticSearchCard.kt** — «Smart search» карточка с голубым `GoogleColors.Blue` на светлом фоне  
  → Убедиться, что контраст ≥ 3:1 (для крупного текста)  
  → Тест: запустить на Android с включённым TalkBack и проверить читаемость

---

### 1.4. Поддержка screen readers

✅ **Хорошо:**  
- Все `TextField`, `Button`, `IconButton` автоматически поддерживаются TalkBack/VoiceOver  
- `EmptyState` использует `Column` с явным порядком фокуса (иконка → заголовок → текст → действия)

⚠️ **Улучшить:**  
- **LibraryScreen.kt:323** — карточки книг в списке:  
  Добавить `.semantics(mergeDescendants = true) { contentDescription = "$title by $author" }`,  
  чтобы TalkBack читал одним блоком, а не по частям.

---

## 2. Отзывчивость и Edge Cases

### 2.1. Длинные тексты

✅ **Хорошо:**  
- Везде используются `maxLines` + `overflow = TextOverflow.Ellipsis`:
  - Заголовки книг: `maxLines = 2`
  - Авторы: `maxLines = 1`
  - Описания: `maxLines = 3-5`

⚠️ **Потенциальные проблемы:**  
1. **Common.kt:164** — обложка книги, `title` на сгенерированной обложке:  
   `maxLines = 3`, но при очень длинном заголовке может выходить за границы  
   → Добавить `Modifier.heightIn(max = 80.dp)` для ограничения высоты блока

2. **LibraryScreen.kt:372** — заголовок в детальном view веб-романа:  
   `maxLines = 2` на `titleLarge` — может быть слишком крупным на узких экранах  
   → Проверить на экране 320dp (старые Android)

3. **ChatScreen.kt:201** — поле ввода вопроса:  
   `maxLines = 5` — хорошо, но нет визуального индикатора скролла внутри поля  
   → Рассмотреть добавление `Modifier.heightIn(max = 120.dp).verticalScroll()`

---

### 2.2. Пустые состояния

✅ **Отлично:**  
- **LibraryScreen.kt:207** — `EmptyState` с иконкой, заголовком, текстом и действиями  
- **CatalogScreen.kt:165** — пустая полка каталога  
- **SettingsScreen.kt** (bookmarks) — «No bookmarks yet»

🔍 **Проверить:**  
- **ChatScreen.kt** — если чат пуст, показывается приветствие + примеры вопросов ✅  
- **ContentSearchSheet.kt** — если поиск по тексту книги не нашёл результатов:  
  → Сейчас просто пустой список. Добавить `EmptyState` с текстом «Ничего не найдено».

---

### 2.3. Состояния загрузки

✅ **Хорошо:**  
- **Common.kt:196** — `ProcessingDialog` для долгих операций (импорт, OCR)  
- **ModelsScreen.kt** — прогресс загрузки модели с процентами и скоростью  
- **LibraryScreen.kt** — индикатор загрузки веб-романов

⚠️ **Улучшить:**  
- **SemanticSearchCard.kt:96** — «Checking file integrity» и «Starting the model» — нет анимации  
  → Добавить `CircularProgressIndicator` рядом с текстом состояния

---

### 2.4. Ошибки и fallback

✅ **Хорошо:**  
- **ChatScreen.kt** — ошибки генерации показываются красным чипом «Generation error»  
- **ModelsScreen.kt** — ошибки загрузки моделей с кнопкой «Repeat»  
- **WebScreen.kt** — ошибки загрузки страниц

🔍 **Проверить:**  
- **BookImage.kt** — если обложка не загрузилась, показывается сгенерированная ✅  
- **ReaderView.kt** — если книга не открылась:  
  → Проверить, что есть понятное сообщение и кнопка «Назад в библиотеку»

---

## 3. Material 3 Expressive Consistency

### 3.1. Цветовая схема

✅ **Отлично:**  
- **Theme.kt** — полная палитра Material 3 для Light/Dark/Black/Sepia/HighContrast  
- Используются динамические цвета Android 12+ (`dynamicLightColorScheme`)  
- Фолбэк на статические цвета для старых версий Android и iOS

🟢 **Рекомендации:**  
- Все компоненты используют `MaterialTheme.colorScheme` — нет хардкоженых цветов ✅  
- Исключения: `GoogleColors.Blue` (#1A73E8) для акцентов AI-функций — **оставить как бренд-цвет**

---

### 3.2. Типографика

✅ **Хорошо:**  
- Используется `MaterialTheme.typography`: `displayLarge`, `titleLarge`, `bodyMedium`, `labelSmall`  
- Нет хардкоженых `fontSize` и `fontWeight` вне темы

⚠️ **Проверить:**  
- **ReaderView.kt** — пользовательский шрифт для текста книги (настраивается)  
  → Убедиться, что при смене системного размера шрифта (Android accessibility settings)  
     размеры UI элементов (кнопки, меню) тоже масштабируются ✅ (используются `sp`)

---

### 3.3. Формы и тени

✅ **Хорошо:**  
- **Theme.kt:239** — `Shapes` с `RoundedCornerShape`:
  - `extraSmall = 4.dp`, `small = 8.dp`, `medium = 12.dp`, `large = 16.dp`, `extraLarge = 28.dp`  
- Все карточки используют `MaterialTheme.shapes.medium` / `large`

🟢 **Согласованность:**  
- Кнопки: `CircleShape` (FAB), `RoundedCornerShape(12.dp)` (обычные)  
- Карточки: `RoundedCornerShape(12.dp)`  
- Диалоги: `RoundedCornerShape(28.dp)` (Material 3 default)

---

### 3.4. Анимации

⚠️ **Проблемы:**  

1. **Отсутствие motion в списках:**  
   - **LibraryScreen.kt**, **CatalogScreen.kt**, **ChatScreen.kt** — `LazyColumn` без `animateItemPlacement()`  
   → Добавить:
   ```kotlin
   LazyColumn(modifier = modifier) {
       items(items, key = { it.id }) { item ->
           Card(modifier = Modifier.animateItemPlacement()) { ... }
       }
   }
   ```

2. **E-ink режим:**  
   - **ReaderView.kt** — настройка «E-ink mode: no animations» ✅  
   - Но проверить, что ВСЕ анимации отключаются (включая ripple-эффекты)  
   → Возможно, нужно обернуть `MaterialExpressiveTheme` в кастомный `MotionScheme` с нулевыми длительностями

3. **Reduced motion (accessibility):**  
   - Сейчас нет проверки системной настройки `prefers-reduced-motion`  
   → Добавить в `Theme.kt`:
   ```kotlin
   val motionScheme = if (isReducedMotionEnabled()) {
       MotionScheme(defaultEffectsSpec = spring(stiffness = Spring.StiffnessHigh))
   } else {
       MotionScheme.expressive()
   }
   ```

4. **Page turn animation:**  
   - **ReaderView.kt** — настройка включения/выключения анимации перелистывания ✅  
   - Проверить, что анимация отключается при `reducedMotion` в системе

---

## 4. UX Patterns

### 4.1. Навигация

✅ **Хорошо:**  
- Bottom navigation bar с иконками и подписями (`Library`, `Search`, `Web`, `Chat`, `Settings`)  
- Навигация назад через системную кнопку и top app bar  
- `TopAppBar` с заголовком и действиями на каждом экране

🟢 **Соответствует Material 3 guidelines**

---

### 4.2. Обратная связь (feedback)

✅ **Хорошо:**  
- Кнопки имеют ripple-эффект (Material 3 default)  
- Snackbar для копирования текста («Copied»), ошибок генерации, etc.  
- `CircularProgressIndicator` для загрузок

⚠️ **Улучшить:**  
- **LibraryScreen.kt:258** — FAB `Add` при нажатии открывает bottom sheet  
  → Нет визуального feedback'а между нажатием и открытием sheet'а (на медленных устройствах может казаться, что ничего не произошло)  
  → Добавить `hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)` при нажатии

---

### 4.3. Жесты

✅ **Хорошо:**  
- **ReaderView.kt** — свайпы, тапы, pinch-to-zoom, drag brightness/font size  
- Настройки тап-зон (ширина боковых зон)  
- Swipe-to-dismiss в списках? → **Проверить:** сейчас не видел `SwipeToDismiss` в коде  
  → Рассмотреть добавление для удаления книг из библиотеки

---

### 4.4. Клавиатура и фокус

✅ **Хорошо:**  
- `TextField` с `KeyboardOptions(imeAction = ImeAction.Search)`  
- `KeyboardActions(onAny = { onSubmit() })`  
- `focus.requestFocus()` в диалогах поиска

⚠️ **Проверить:**  
- **ChatScreen.kt** — при отправке вопроса клавиатура должна скрываться  
  → Проверить на Android: после нажатия «Send» клавиатура прячется автоматически?

---

## 5. Производительность

### 5.1. Ненужные рекомпозиции

✅ **Хорошо:**  
- Везде используются `remember` с правильными ключами:
  - `remember(book) { ... }` — пересчёт только при смене книги  
  - `remember(filtered) { groupLibrary(filtered) }` — мемоизация группировки  
  - `remember(entryId, ref) { ... }` — ленивая загрузка обложек

🔍 **Проверить:**  
1. **App.kt:222** — `LaunchedEffect(indexKey)` запускает синхронизацию индекса  
   → Убедиться, что `indexKey` меняется ТОЛЬКО при реальном изменении индекса, а не при каждой рекомпозиции

2. **LibraryScreen.kt:162** — `remember(filtered) { groupLibrary(filtered) }`  
   → Если `filtered` — это `List<Entry>`, то при каждом изменении списка (добавление книги) пересчитывается группировка  
   → Оптимизация: использовать `derivedStateOf` вместо `remember`:
   ```kotlin
   val shelf by remember { derivedStateOf { groupLibrary(filtered) } }
   ```

3. **ReaderView.kt** — 910+ строк, огромный Composable  
   → Рассмотреть разбиение на подкомпоненты:
     - `ReaderToolbar`, `ReaderBottomPanel`, `ReaderContent`, `ReaderGestureDetector`  
     → Каждый с явными параметрами и `Modifier.stable`

---

### 5.2. Ленивые списки

✅ **Хорошо:**  
- Везде используются `LazyColumn` и `LazyRow` для списков книг, глав, настроек  
- `items(items, key = { it.id })` — stable keys для анимаций

⚠️ **Потенциальная проблема:**  
- **CatalogScreen.kt** — каталог может содержать тысячи книг  
  → Проверить, что используется пагинация или виртуализация  
  → Если нет — добавить лимит на отображаемые элементы (например, `items.take(100)`) с кнопкой «Load more»

---

### 5.3. Изображения

✅ **Хорошо:**  
- **BookImage.kt** — ленивая загрузка обложек через `rememberCover`  
- Используется кэш (`ImageState.Loading` → `Loaded` / `Failed`)  
- `maxDimension` ограничивает размер загружаемых изображений

🟢 **Производительность обложек — отлично**

---

### 5.4. Memory leaks

✅ **Хорошо:**  
- **App.kt:273** — `DisposableEffect(reading.entryId) { onDispose { ... } }` правильно очищает ресурсы  
- Все `LaunchedEffect` с правильными зависимостями

🔍 **Проверить:**  
- **ReaderView.kt** — большой composable с множеством `remember`, `LaunchedEffect`, pointer input  
  → Запустить Android Studio Memory Profiler и проверить, что при выходе из Reader'а все ресурсы освобождаются

---

## 6. Платформенные особенности

### 6.1. Android

✅ **Хорошо:**  
- Динамические цвета Android 12+  
- System bars (status bar, navigation bar) настроены через `Theme.kt`  
- Поддержка разных размеров экранов (телефоны, планшеты, foldables)

⚠️ **Проверить:**  
- **Landscape orientation** — все экраны корректно отображаются в альбомной ориентации?  
  → Особенно **ReaderView.kt** и **ChatScreen.kt**

---

### 6.2. iOS

⚠️ **Потенциальные проблемы:**  

1. **Safe areas:**  
   - Проверить, что контент не перекрывается notch'ем и home indicator'ом  
   → Добавить в `App.kt`:
   ```kotlin
   Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
   ```

2. **Dark mode:**  
   - iOS использует системную тему (Light/Dark)  
   → Проверить, что `isSystemInDarkTheme()` корректно работает на iOS

3. **Keyboard avoidance:**  
   - На iOS клавиатура может перекрывать `TextField`  
   → Проверить, что используется `Modifier.imePadding()`

---

## 7. Приоритетные фиксы для 2.10.0

### Критичные (блокеры релиза)

1. ❌ **Нет критичных блокеров** — основной UI/UX в порядке

---

### Высокий приоритет (желательно в 2.10.0)

1. **Добавить contentDescription для смысловых иконок (11 шт.)**  
   - Файлы: `ChatScreen.kt`, `LibraryScreen.kt`, `ModelsScreen.kt`, `WebScreen.kt`, `ReaderView.kt`  
   - Требуются новые строковые ресурсы: `select_book_context`, `download_chapter`, etc.

2. **Добавить `EmptyState` в поиске по тексту книги**  
   - Файл: `ContentSearchSheet.kt`  
   - Текст: «Ничего не найдено» + иконка

3. **Добавить `animateItemPlacement()` в списки**  
   - Файлы: `LibraryScreen.kt`, `CatalogScreen.kt`, `ChatScreen.kt`

4. **Добавить haptic feedback на FAB и важные действия**  
   - Файл: `LibraryScreen.kt:258` (FAB Add)

5. **Проверить reduced motion support**  
   - Файл: `Theme.kt` — добавить `isReducedMotionEnabled()` и кастомный `MotionScheme`

---

### Средний приоритет (можно отложить на 2.11.0)

6. **Оптимизировать ReaderView.kt** — разбить на подкомпоненты  
7. **Добавить семантику для тап-зон в Reader'е**  
8. **Проверить landscape orientation на всех экранах**  
9. **Проверить iOS safe areas и keyboard avoidance**  
10. **Оптимизировать группировку библиотеки** — использовать `derivedStateOf`

---

### Низкий приоритет (улучшения UX)

11. **Добавить swipe-to-dismiss для удаления книг**  
12. **Добавить пагинацию в каталоге** (если >100 книг)  
13. **Добавить визуальный индикатор скролла в поле ввода чата**  
14. **Ограничить высоту заголовка на обложке** (`Common.kt:164`)

---

## 8. Рекомендации по тестированию

### Ручное тестирование

1. **TalkBack (Android) / VoiceOver (iOS):**  
   - Пройти весь флоу: добавление книги → чтение → поиск → настройки  
   - Убедиться, что все интерактивные элементы озвучиваются

2. **Edge cases:**  
   - Книга с очень длинным названием (100+ символов)  
   - Автор с китайскими/арабскими символами  
   - Пустая библиотека  
   - Библиотека с 1000+ книг  
   - Медленная сеть (веб-романы)

3. **Разные размеры экранов:**  
   - Узкий (320dp, старые Android)  
   - Средний (411dp, Pixel)  
   - Широкий (планшеты, 600dp+)  
   - Landscape orientation

4. **Системные настройки:**  
   - Large font size (Android accessibility)  
   - Display zoom (Android)  
   - Dark mode  
   - Reduced motion (iOS)

---

### Автоматизированное тестирование

1. **Accessibility scanner (Android Studio):**  
   - Запустить на всех экранах  
   - Проверить контрастность, размеры touch targets (min 48dp)

2. **Compose UI tests:**  
   - Добавить тесты для `EmptyState`, `BookCover`, `ProcessingDialog`  
   - Проверить, что все `Button` и `IconButton` кликабельны

3. **Screenshot testing:**  
   - Снять скриншоты всех экранов в Light/Dark/HighContrast темах  
   - Сравнить с reference images — детектить визуальные регрессии

---

## 9. Выводы

**Общая оценка UI/UX:** ⭐⭐⭐⭐☆ (4 из 5)

### Сильные стороны:
- ✅ Полная реализация Material 3 Expressive  
- ✅ Отличная типографика и цветовая схема  
- ✅ Хорошая обработка edge cases (длинные тексты, пустые состояния)  
- ✅ Ленивая загрузка и оптимизация производительности  
- ✅ E-ink режим и настройки для разных типов экранов  

### Что нужно улучшить:
- ⚠️ Accessibility: 11 иконок без contentDescription  
- ⚠️ Анимации: нет `animateItemPlacement()` в списках  
- ⚠️ Reduced motion: не проверяется системная настройка  
- ⚠️ ReaderView.kt слишком большой (910+ строк) — нужна декомпозиция  

### Рекомендация:
**Можно выпускать 2.10.0** после фикса пунктов 1-5 из «Высокого приоритета» (2-3 часа работы).  
Остальные пункты — не блокеры, можно отложить на следующие версии.

---

**Следующий шаг:** Создать issues в `ROADMAP.md` для каждого пункта из «Приоритетных фиксов».
