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

    private enum Gate: Equatable { case auth, pending, root }
    private var gate: Gate {
        if !cloud.claimed { return .auth }
        if !cloud.approved { return .pending }
        return .root
    }

    var body: some Scene {
        WindowGroup {
            Group {
                switch gate {
                case .auth: AuthView()                       // the app belongs to an account — sign in first
                        .transition(.opacity)
                case .pending: PendingView()                 // a member's device waits for an admin
                        .transition(.opacity)
                case .root: RootView()                       // the printer + Sheets/Settings on the bottom bar
                        .transition(.opacity.combined(with: .scale(scale: 0.98)))
                }
            }
            // a gentle cross-fade when the gate changes, so sign-in → main isn't a hard cut
            .animation(.easeInOut(duration: 0.45), value: gate)
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
