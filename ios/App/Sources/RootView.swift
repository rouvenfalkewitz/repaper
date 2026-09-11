import SwiftUI

/// The claimed+approved app: three destinations on a floating bottom bar — a compact
/// rounded pill that hovers just above the home indicator (the iOS-native floating tab
/// bar look). Sheets on the left, Settings on the right, and the Printer as a raised
/// round home button wearing the glowing brand ring with "GO" in the CI letters.
/// Content is inset via safeAreaInset, so nothing hides behind the pill.
struct RootView: View {
    @StateObject private var nav = Nav()

    var body: some View {
        ZStack {
            Ui.bg.ignoresSafeArea()
            switch nav.tab {
            case .sheets: SheetsView()
            case .printer: MainView()
            case .settings: SettingsView()
            }
        }
        .environmentObject(nav)
        .safeAreaInset(edge: .bottom, spacing: 0) { FloatingBar(tab: $nav.tab) }
    }
}

/// A floating rounded pill: a soft carbon capsule with a hairline and a drop shadow that
/// hovers above the home indicator, with the round printer button lifted into the middle.
private struct FloatingBar: View {
    @Binding var tab: RootTab

    private let pillHeight: CGFloat = 60
    private let discSize: CGFloat = 64
    // the CI ring: the logo's stroke is ~9.6% of its ring diameter — match it here
    private let ringStroke: CGFloat = 6

    var body: some View {
        ZStack {
            // the pill
            HStack(spacing: 0) {
                tabItem(.sheets, "square.stack", "Sheets")
                Spacer(minLength: 0)
                Color.clear.frame(width: discSize)   // the middle belongs to the printer
                Spacer(minLength: 0)
                tabItem(.settings, "gearshape", "Settings")
            }
            .padding(.horizontal, 22)
            .frame(height: pillHeight)
            .background(
                RoundedRectangle(cornerRadius: pillHeight / 2, style: .continuous)
                    .fill(Ui.surface)
                    .overlay(
                        RoundedRectangle(cornerRadius: pillHeight / 2, style: .continuous)
                            .stroke(Ui.border, lineWidth: 1)
                    )
                    .shadow(color: .black.opacity(0.5), radius: 18, y: 8)
            )

            // the printer home — centred in the pill, wearing the glowing brand ring
            printerButton
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 10)   // room for the glow to bloom, top and bottom
    }

    private func tabItem(_ t: RootTab, _ icon: String, _ label: String) -> some View {
        let active = tab == t
        return Button {
            withAnimation(.easeOut(duration: 0.15)) { tab = t }
        } label: {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: active ? .semibold : .regular))
                Text(label).font(Ui.mono(9))
            }
            .foregroundColor(active ? Ui.accent : Ui.text3)
            .frame(width: 62)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
    }

    private var printerButton: some View {
        let active = tab == .printer
        return Button {
            withAnimation(.easeOut(duration: 0.15)) { tab = .printer }
        } label: {
            ZStack {
                // carbon puck
                Circle()
                    .fill(LinearGradient(colors: [Ui.surface2, Ui.bg],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: discSize, height: discSize)
                // the glowing brand ring, as the button's own outer ring — thick like the logo
                Circle()
                    .stroke(Ui.accent, lineWidth: ringStroke)
                    .frame(width: discSize - ringStroke, height: discSize - ringStroke)
                    .shadow(color: Ui.accent.opacity(active ? 0.8 : 0.55),
                            radius: active ? 16 : 11)
                // GO in the CI letterforms (Archivo wght 800 / wdth 125, like the lockup)
                Text("GO")
                    .font(Ui.display(22, weight: 800, width: 125)).kerning(-0.5)
                    .foregroundColor(active ? Ui.accent : Ui.text)
            }
        }
        .buttonStyle(PressScale())
    }
}

/// A press effect that scales instead of fading — so a raised button never turns
/// translucent and reveals the bar behind it.
private struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}
