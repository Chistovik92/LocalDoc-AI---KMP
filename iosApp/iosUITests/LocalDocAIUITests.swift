import XCTest

/// Смоук реального приложения: Compose-контроллер поднят и на экране есть доступные элементы.
final class LocalDocAIUITests: XCTestCase {
    @MainActor
    func testApplicationLaunchesWithContent() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))
        XCTAssertFalse(app.descendants(matching: .any).allElementsBoundByIndex.isEmpty)
    }
}
