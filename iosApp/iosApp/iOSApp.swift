import SwiftUI
import UIKit
import UserNotifications
import PersonalkaUI

/// «Персоналка» на iPhone (шаг 5.1): всё приложение — общие экраны Kotlin (модуль shared/ui, фреймворк PersonalkaUI).
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // База, обмен и уведомления наперёд — до первого экрана.
        MainViewControllerKt.startPersonalka()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { phase in
            // Возврат в приложение: обмен сразу, прошедшие закрываются, уведомления ставятся заново.
            if phase == .active { MainViewControllerKt.onAppActive() }
        }
    }
}

/// Нажатое уведомление ведёт на свой экран; пока приложение открыто, уведомление тоже показывается.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        // Кнопка «Дневник» у сводки — первая тренировка без дневника, само уведомление — его экран.
        let key = response.actionIdentifier == "diary" ? "diary" : "link"
        MainViewControllerKt.openLink(code: info[key] as? String)
        completionHandler()
    }
}
