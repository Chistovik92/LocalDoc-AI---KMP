# Время сборок CI (GitHub Actions, runner macOS для iOS)

## Сборка iOS (xcframework + приложение) — полный pipeline

- Быстрая проверка Kotlin/Native: ~4–6 мин
- Полная iOS-сборка (XCFramework + приложение, симулятор + устройство): **~39 минут**
  - Шаги: C-ядро llama.cpp (Metal) → sherpa-onnx → компиляция Kotlin/Native → XcodeGen + сборка приложения (симулятор) → сборка для устройства (arm64) → упаковка релиза → публикация артефактов
- Сборка Android APK: ~5–7 мин

Зафиксировано 2026-10-31 при релизе 2.4.0 (run 41230510931).
