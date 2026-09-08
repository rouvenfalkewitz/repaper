// RePaper Go for iOS — the share-first sibling of the Android app.
// STATUS: scaffold. RePaperKit (the protocol core) is complete; this shell grows in the
// first Xcode session: sign-in gate → auto-claim, the ring main screen, sheets via QR/NFC,
// share extension for content intake, CoreBluetooth OdLink.
import SwiftUI

@main
struct RePaperGoApp: App {
    var body: some Scene {
        WindowGroup { MainView() }
    }
}

/// The Paper design system, iOS edition — same tokens as brand/tokens.css.
enum Ui {
    static let bg = Color(red: 0x0C / 255, green: 0x10 / 255, blue: 0x0F / 255)
    static let surface = Color(red: 0x15 / 255, green: 0x1A / 255, blue: 0x18 / 255)
    static let surface2 = Color(red: 0x1C / 255, green: 0x23 / 255, blue: 0x20 / 255)
    static let border = Color(red: 0x27 / 255, green: 0x30 / 255, blue: 0x29 / 255)
    static let borderStrong = Color(red: 0x36 / 255, green: 0x41 / 255, blue: 0x39 / 255)
    static let text = Color(red: 0xEF / 255, green: 0xF1 / 255, blue: 0xEE / 255)
    static let text2 = Color(red: 0x9A / 255, green: 0xA5 / 255, blue: 0xA0 / 255)
    static let text3 = Color(red: 0x5E / 255, green: 0x6A / 255, blue: 0x64 / 255)
    static let accent = Color(red: 0x1E / 255, green: 0xE3 / 255, blue: 0xA5 / 255)
}

/// The light ring in its box — the LED language, SwiftUI edition (ready state: breathe, dim).
struct RingView: View {
    @State private var phase = false
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 37)
                .fill(LinearGradient(colors: [Ui.surface2, Ui.bg], startPoint: .topLeading, endPoint: .bottomTrailing))
                .overlay(RoundedRectangle(cornerRadius: 37).stroke(Ui.borderStrong, lineWidth: 1))
                .frame(width: 148, height: 148)
            Circle()
                .stroke(Ui.accent, lineWidth: 8.3)
                .frame(width: 86.7, height: 86.7)
                .opacity(phase ? 0.48 : 0.18)
                .shadow(color: Ui.accent.opacity(0.5), radius: 14)
                .animation(.easeInOut(duration: 1.5).repeatForever(autoreverses: true), value: phase)
        }
        .onAppear { phase = true }
    }
}

struct MainView: View {
    var body: some View {
        ZStack {
            Ui.bg.ignoresSafeArea()
            VStack(spacing: 8) {
                RingView().padding(.top, 40)
                Text("RePaper Go")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(Ui.text)
                    .padding(.top, 18)
                Text("The iOS app is under construction —\nthe protocol core is already on board.")
                    .font(.system(size: 14))
                    .foregroundColor(Ui.text2)
                    .multilineTextAlignment(.center)
                Spacer()
            }
        }
    }
}
