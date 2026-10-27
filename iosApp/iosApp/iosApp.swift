import SwiftUI
import ComposeApp

@main
struct LocalDocAIApp: App {
    init() {
        // Фоновая проверка новых глав: задачу нужно зарегистрировать до окончания запуска
        MainViewControllerKt.registerBackgroundTasks()
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
