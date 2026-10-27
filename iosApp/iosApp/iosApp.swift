import SwiftUI
import ComposeApp

@main
struct LocalDocAIApp: App {
    init() {
        // Кнопки виджета (App Intents, iOS 17+) управляют плеером озвучки в процессе приложения
        PlayerBridge.handler = { MainViewControllerKt.playerCommand(name: $0) }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // Виджет (localdoc://…), файлы книг из «Файлов» и ссылки «Поделиться»
                .onOpenURL { url in
                    MainViewControllerKt.handleOpenUrl(url: url.isFileURL ? url.path : url.absoluteString)
                }
        }
    }
}

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
