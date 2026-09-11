import SwiftUI

/// A member's phone between claiming and admin approval: the ring blinks Signal Blue
/// (the LED language's setup state) and the app stays gated until the fleet says go.
/// A quiet "sign out" escape lets them back out if they landed here by mistake.
struct PendingView: View {
    @EnvironmentObject var cloud: CloudAgent
    @State private var signingOut = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                RingBox { RingView(led: .setup) }
                    .padding(.top, 48)
                Pill(text: "Waiting for approval", fg: Ui.amber, bg: Ui.amberTint, blinkMs: 500)
                    .padding(.top, 18)
                Text("Almost there")
                    .font(Ui.display(24, weight: 800, width: 125)).kerning(-0.4)
                    .foregroundColor(Ui.text)
                    .padding(.top, 10)
                Text("An administrator of \(cloud.org ?? "your organisation") needs to approve this device in the fleet console. This page moves on by itself the moment that happens.")
                    .font(Ui.body(14)).foregroundColor(Ui.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12).padding(.top, 6)

                Button { Task { await signOut() } } label: {
                    Text(signingOut ? "Signing out…" : "Not this device? Sign out")
                        .font(Ui.body(14, weight: 600)).foregroundColor(Ui.text3).underline()
                }
                .disabled(signingOut)
                .padding(.top, 34)
            }
            .padding(.horizontal, 24).padding(.bottom, 28)
        }
        .background(Ui.bg.ignoresSafeArea())
        .onAppear { cloud.start() }   // the approval arrives as a push on the device channel
    }

    /// Back out of a device that's waiting: unclaim server-side, then drop back to sign-in.
    private func signOut() async {
        signingOut = true
        defer { signingOut = false }
        var req = URLRequest(url: URL(string: "\(Prefs.cloudBase)/api/device/unclaim")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: [
            "id": Identity.shared.deviceId, "secret": Identity.shared.secret])
        _ = try? await URLSession.shared.data(for: req)
        Prefs.claimed = false
        cloud.claimed = false   // the root router returns to the sign-in gate
    }
}
