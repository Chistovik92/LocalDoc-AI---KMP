import AppIntents
import SwiftUI
import WidgetKit

/// Виджет рабочего стола LocalDoc AI: слушать книгу, открыть файл, открыть ссылку.
/// Кнопки — deep link localdoc://…, их обрабатывает приложение (.onOpenURL → Kotlin).
struct ReaderEntry: TimelineEntry {
    let date: Date
}

struct ReaderProvider: TimelineProvider {
    func placeholder(in context: Context) -> ReaderEntry { ReaderEntry(date: Date()) }

    func getSnapshot(in context: Context, completion: @escaping (ReaderEntry) -> Void) {
        completion(ReaderEntry(date: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ReaderEntry>) -> Void) {
        completion(Timeline(entries: [ReaderEntry(date: Date())], policy: .never))
    }
}

private let googleBlue = Color(red: 11 / 255, green: 87 / 255, blue: 208 / 255)

struct ActionButton: View {
    let title: String
    let systemImage: String
    let url: URL
    var prominent = false

    var body: some View {
        Link(destination: url) {
            VStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: prominent ? 22 : 18, weight: .semibold))
                    .frame(width: prominent ? 52 : 44, height: prominent ? 52 : 44)
                    .background(prominent ? googleBlue : Color.secondary.opacity(0.15))
                    .foregroundColor(prominent ? .white : .primary)
                    .clipShape(Circle())
                Text(title)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

/// Круглая кнопка плеера: iOS 17+ — App Intent (управляет озвучкой, не открывая приложение).
@available(iOS 17.0, *)
struct PlayerButton<I: AppIntent>: View {
    let intent: I
    let systemImage: String
    var prominent = false

    var body: some View {
        Button(intent: intent) {
            Image(systemName: systemImage)
                .font(.system(size: prominent ? 22 : 18, weight: .semibold))
                .frame(width: prominent ? 52 : 44, height: prominent ? 52 : 44)
                .background(prominent ? googleBlue : Color.secondary.opacity(0.15))
                .foregroundColor(prominent ? .white : .primary)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// Управление плеером в виджете: назад, пауза/продолжить, вперёд.
@available(iOS 17.0, *)
struct PlayerControls: View {
    var body: some View {
        HStack(spacing: 14) {
            PlayerButton(intent: PreviousSentenceIntent(), systemImage: "backward.fill")
            PlayerButton(intent: TogglePlaybackIntent(), systemImage: "playpause.fill", prominent: true)
            PlayerButton(intent: NextSentenceIntent(), systemImage: "forward.fill")
        }
    }
}

private func deepLink(_ path: String) -> URL {
    URL(string: "localdoc://\(path)") ?? URL(fileURLWithPath: "/")
}

struct LocalDocWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "book")
                    .foregroundColor(googleBlue)
                Text("LocalDoc AI")
                    .font(.headline)
            }
            if family != .systemSmall {
                Text("Читалка с озвучкой и локальным ИИ")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer(minLength: 0)
            if #available(iOS 17.0, *), family == .systemSmall {
                // Маленький виджет целиком: только плеер
                PlayerControls()
            }
            HStack(spacing: family == .systemSmall ? 8 : 16) {
                if #available(iOS 17.0, *) {
                    // iOS 17+: «Слушать» заменяют кнопки плеера (в маленьком виджете они выше)
                    if family != .systemSmall { PlayerControls() }
                } else {
                    ActionButton(title: "Слушать", systemImage: "play.fill", url: deepLink("play"), prominent: true)
                }
                if family != .systemSmall {
                    ActionButton(title: "Книга", systemImage: "folder", url: deepLink("open-file"))
                    ActionButton(title: "Ссылка", systemImage: "link", url: deepLink("open-url"))
                    ActionButton(title: "Продолжить", systemImage: "book.closed", url: deepLink("continue"))
                }
            }
        }
        // В маленьком виджете Link не работает — нажатие на виджет (вне кнопок) открывает приложение на последнем месте
        .widgetURL(deepLink("continue"))
        .widgetBackgroundCompat()
    }
}

private extension View {
    @ViewBuilder
    func widgetBackgroundCompat() -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(for: .widget) { Color(UIColor.secondarySystemBackground) }
        } else {
            padding().background(Color(UIColor.secondarySystemBackground))
        }
    }
}

@main
struct LocalDocWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "LocalDocWidget", provider: ReaderProvider()) { _ in
            LocalDocWidgetView()
        }
        .configurationDisplayName("LocalDoc AI")
        .description("Слушать книгу, открыть файл или ссылку")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
