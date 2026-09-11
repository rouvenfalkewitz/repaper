// RePaper Go for iOS — the share-first sibling of the Android app.
// The shell: sign-in gate → auto-claim → (approval) → the ring main screen; sheets and
// cloud agent live. The share extension nudges here with a local notification.
import SwiftUI
import UserNotifications

@main
struct RePaperGoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var cloud = CloudAgent.shared
    @StateObject private var sheets = SheetStore.shared

    var body: some Scene {
        WindowGroup {
            Group {
                if !cloud.claimed { AuthView() }             // the app belongs to an account — sign in first
                else if !cloud.approved { PendingView() }    // a member's device waits for an admin
                else { RootView() }                          // the printer + Sheets/Settings on the bottom bar
            }
            .environmentObject(cloud)
            .environmentObject(sheets)
            .preferredColorScheme(.dark)
        }
    }
}

/// Owns notification plumbing: tapping a "ready to print" notification (from the share
/// extension, later from Print2Go pushes) opens the app and lands on the printer.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// Ask once — the share extension's notifications only show if this is granted.
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])   // show it even if we're foregrounded
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        NotificationCenter.default.post(name: .openPrinter, object: nil)
        completionHandler()
    }
}
