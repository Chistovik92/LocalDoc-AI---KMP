import AppIntents

/// Мост от интентов к Kotlin-плееру. Приложение выставляет `handler` при запуске (iosApp.swift);
/// в процессе виджета он не нужен: `AudioPlaybackIntent` система выполняет в процессе приложения.
enum PlayerBridge {
    static var handler: ((String) -> Void)?
}

/// Интенты управления озвучкой для интерактивного виджета (iOS 17+).
/// Файл входит в обе цели — приложение и виджет: система должна знать тип интента с обеих сторон.
@available(iOS 17.0, *)
struct TogglePlaybackIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Пауза / продолжить"
    static var description = IntentDescription("Ставит озвучку книги на паузу или продолжает чтение.")

    func perform() async throws -> some IntentResult {
        PlayerBridge.handler?("toggle")
        return .result()
    }
}

@available(iOS 17.0, *)
struct NextSentenceIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Следующее предложение"

    func perform() async throws -> some IntentResult {
        PlayerBridge.handler?("next")
        return .result()
    }
}

@available(iOS 17.0, *)
struct PreviousSentenceIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Предыдущее предложение"

    func perform() async throws -> some IntentResult {
        PlayerBridge.handler?("previous")
        return .result()
    }
}
