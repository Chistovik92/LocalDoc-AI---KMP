# LocalDoc AI (KMP)

Кроссплатформенная **читалка** (Android + iOS) книг и веб-страниц с озвучкой и **полностью
локальными** нейросетями. Без облака, без телеметрии — модель и синтез речи работают на устройстве.
Дизайн — в стиле сервисов Google (Material 3 / Material You).

> Проект создан по архитектурному плану из «Диалог Gemini.docx» (LocalDoc AI):
> Kotlin Multiplatform + Compose Multiplatform + единое C-ядро llama.cpp
> (JNI + Vulkan на Android, cinterop + Metal на iOS), локальный RAG, OCR, TTS.

## Возможности

- **Книги всех популярных форматов**: EPUB, FB2 / FB2.ZIP, PDF (текстовый слой + офлайн-OCR для сканов:
  PDFBox + ML Kit на Android, PDFKit + Vision на iOS), Markdown, TXT (UTF-8 / Windows-1251), HTML.
- **Библиотека** в стиле Google Play Книг: обложки, «Продолжить чтение», поиск, фильтры, прогресс.
- **Экран чтения**: темы бумаги, шрифты, интервалы, оглавление, скрываемые панели.
- **Веб в режиме чтения**: страница без рекламы и меню, навигация по главам веб-новелл и их оглавлению;
  если на сайте нет маркеров «Далее» — ссылку находит локальная модель.
- **Озвучка как в плеере**: плей / пауза / стоп, переходы по предложениям и абзацам, подсветка
  читаемого предложения, остановка с предупреждением в конце главы; плеер в шторке уведомлений
  (Android), «Пункт управления» и экран блокировки (iOS), виджеты рабочего стола.
- **Любые голосовые движки**: системные и сторонние/открытые (RHVoice, sherpa-onnx + Piper, eSpeak NG),
  выбор голоса, скорости и высоты.
- **ИИ-суфлёр**: «Суммаризировать страницу», «Объяснить термин», «Найти главное», чат «Ассистент»
  по открытой книге (Smart Router: прямой контекст / Map-Reduce / локальный RAG).
- **15 GGUF-моделей** для смартфонов (Qwen 2.5/3, Gemma 2/3, Llama 3.2, Phi 3.5, SmolLM2, DeepSeek R1
  Distill, Vikhr) с фильтром по ОЗУ и докачкой при обрыве.
- **Обновления** из GitHub Releases прямо в приложении. История версий — в [CHANGELOG.md](CHANGELOG.md).

## Архитектура

```
LocalDoc-AI---KMP/
├── cpp_core/                     # Единое C/C++ ядро для обеих ОС
│   ├── llama.cpp/                # Git submodule (тег b10004)
│   ├── bridge/
│   │   ├── localdoc_c_api.h      # Чистый C-интерфейс (cinterop + JNI)
│   │   └── localdoc_c_api.cpp    # Сессия, сэмплер, стриминг токенов
│   ├── scripts/
│   │   ├── build_ios_xcframework.sh   # llama.cpp + Metal → .xcframework
│   │   └── build_android.sh           # standalone-сборка через NDK
│   └── CMakeLists.txt
├── composeApp/
│   ├── src/commonMain/kotlin/com/localdoc/ai/
│   │   ├── engine/LlamaEngine.kt      # expect class (контракт движка)
│   │   ├── model/ModelCatalog.kt      # Каталог GGUF-моделей
│   │   ├── download/ModelDownloader.kt# expect class (докачка моделей)
│   │   ├── book/                      # Модель книги, парсеры EPUB/FB2/PDF/MD/TXT/HTML, ZIP, библиотека,
│   │   │                              #   Readability + фильтр рекламы, навигация веб-новелл
│   │   ├── parser/                    # Web (Ksoup) + expect PdfExtractor
│   │   ├── rag/                       # Chunking + эмбеддинги + векторный поиск
│   │   ├── domain/                    # SmartPipeline (Smart Router), AppGraph
│   │   ├── tts/                       # Speaker (expect, любые TTS-движки) + ReadAloudController (плеер)
│   │   ├── update/                    # Проверка обновлений (GitHub Releases)
│   │   └── ui/                        # Единый UI на Compose Multiplatform
│   ├── src/androidMain/               # JNI, PDFBox+ML Kit, Share Intent, TTS, плеер в шторке, виджет
│   │   └── cpp/ (CMakeLists.txt, android_jni.cpp)
│   ├── src/iosMain/                   # cinterop, PDFKit+Vision, AVSpeech
│   └── src/nativeInterop/cinterop/llama.def
├── iosApp/                            # Xcode-проект (XcodeGen: project.yml) + iosWidget (WidgetKit)
├── signing/localdoc.keystore          # CI-ключ подписи APK (test signing)
└── .github/workflows/                 # CI: Android APK + iOS framework
```

Ключевые решения:

- **Единый C-мост `localdoc_c_api.h`** — JNI (Android) и cinterop (iOS) вызывают одни и те
  же функции; бизнес-логика инференса не дублируется.
- **mmap-загрузка весов** — ОС может выгружать холодные страницы без краша приложения.
- **RAG без нативных зависимостей**: чанки + L2-нормализованные векторы (feature hashing,
  384 измерения) в JSON-хранилище; косинусный top-K поиск. Интерфейс `EmbeddingProvider`
  совместим с заменой на ONNX-модель (multilingual-e5-small) без изменения остального кода.
- **Промпт-шаблон ChatML** (`<|im_start|>`…`<|im_end|>`) под Qwen 2.5 Instruct.

## Сборка Android

Требуется JDK 17/21, Android SDK + NDK (arm64-v8a), CMake 3.22+.

```bash
# Android Studio: открыть корень репозитория → Run.
# Или из консоли:
./gradlew :composeApp:assembleDebug     # debug-APK
./gradlew :composeApp:assembleRelease   # подписан CI-ключом из signing/
```

По умолчанию llama.cpp собирается с CPU-бэкендом (dotprod/fp16 на Snapdragon).
Vulkan-ускорение (Adreno GPU) включается локально, если на хосте установлен
Vulkan SDK (glslc + SPIRV-Headers):

```bash
./gradlew :composeApp:assembleDebug -Plocaldoc.vulkan=true
```

APK появится в `composeApp/build/outputs/apk/<variant>/`.

Первый запуск: «Книги» → «Добавить» → файл книги (или вкладка «Веб» → адрес страницы) → «Слушать».
Для ИИ: «Модели» → установите, например, Qwen 3 1.7B → «Использовать».

## Сборка iOS

Требуется macOS с Xcode 15+, CMake, JDK 17.

```bash
# 1. C-ядро (llama.cpp + Metal) → XCFramework
./cpp_core/scripts/build_ios_xcframework.sh

# 2. Kotlin/Native-фреймворк + генерация Xcode-проекта
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64   # симулятор
./gradlew :composeApp:linkReleaseFrameworkIosArm64          # устройство
brew install xcodegen
cd iosApp && xcodegen generate
open LocalDocAI.xcodeproj
```

- Схема **LocalDocAI** — запуск на симуляторе (Apple Silicon);
- Схема **LocalDocAIDevice** — архив `.ipa` для устройств (нужна своя команда
  подписи Apple Developer).

## CI/CD

- `.github/workflows/android.yml` — на каждый push/PR гоняет unit-тесты (`commonTest`) и собирает debug-APK; на тег `v*`
  собирает release-APK, подписанный постоянным ключом, и прикладывает его к GitHub Release.
- `.github/workflows/ios.yml` — собирает `localdoc_core.xcframework` (Metal) и
  ComposeApp-фреймворк для симулятора, проверяет сборку Xcode-проекта без подписи.

Релиз:

```bash
git tag v1.5.0 && git push origin v1.5.0
```

## Подпись release-сборок

Release-APK подписывается постоянным ключом (PKCS12, alias `localdoc-release`). Ключ и пароли
в репозиторий не попадают:

- локально Gradle читает `~/.android-signing/localdoc-ai/signing.properties`
  (другой путь — `-Plocaldoc.signing=/path/signing.properties`);
- в CI ключ берётся из секретов `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; без них сборка по тегу завершается ошибкой.

Отпечаток сертификата (SHA-256):
`34:E5:59:D7:AD:01:43:72:D5:61:BC:06:00:3A:1C:D1:E0:57:EA:4E:7D:32:31:DB:4A:FF:5A:13:9F:77:5E:54`

## Ограничения и дорожная карта

- PDF отображается как текст (reflow), без постраничного рендера с картинками; изображения EPUB не показываются.
- Эмбеддинги RAG — лексические (feature hashing); замена на ONNX e5 — точка расширения.
- Встроенный нейросетевой синтез (Piper через sherpa-onnx внутри приложения) — следующий шаг;
  сейчас Piper-голоса подключаются через движок sherpa-onnx TTS Engine.
- iOS-виджет открывает приложение по ссылке; управление плеером прямо из виджета (App Intents) — запланировано.

## Лицензия

MIT (см. LICENSE). llama.cpp — отдельный проект со своей лицензией MIT, подключён сабмодулем.
