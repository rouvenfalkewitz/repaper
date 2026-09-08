import SwiftUI

/// A member's phone between claiming and admin approval: the ring blinks Signal Blue
/// (the LED language's setup state) and the app stays gated until the fleet says go.
struct PendingView: View {
    @EnvironmentObject var cloud: CloudAgent

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                RingBox { RingView(led: .setup) }
                    .padding(.top, 48)
                Pill(text: "Waiting for approval", fg: Ui.amber, bg: Ui.amberTint, blinkMs: 500)
                    .padding(.top, 18)
                Text("Almost there")
                    .font(Ui.display(22, weight: 700)).foregroundColor(Ui.text)
                    .padding(.top, 8)
                Text("An administrator of \(cloud.org ?? "your organisation") needs to approve this device in the fleet console. This page moves on by itself the moment that happens.")
                    .font(Ui.body(14)).foregroundColor(Ui.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12).padding(.top, 4)
            }
            .padding(.horizontal, 24).padding(.bottom, 28)
        }
        .background(Ui.bg.ignoresSafeArea())
        .onAppear { cloud.start() }   // the approval arrives as a push on the device channel
    }
}
