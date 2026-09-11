// RePaper Go for iOS — the share-first sibling of the Android app.
// The shell: sign-in gate → auto-claim → (approval) → the ring main screen; sheets and
// cloud agent live. Next: share extension for content intake, QR/NFC sheet adding.
import SwiftUI

@main
struct RePaperGoApp: App {
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
            .onOpenURL { url in
                // the share extension launches us at repaper-go://print — land on the
                // printer with the freshly shared job ready to go
                if url.scheme == "repaper-go" {
                    NotificationCenter.default.post(name: .openPrinter, object: nil)
                }
            }
        }
    }
}
