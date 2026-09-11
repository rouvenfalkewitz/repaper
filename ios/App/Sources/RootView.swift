import SwiftUI

/// The claimed+approved app: three destinations on a floating bottom bar — Sheets on
/// the left, Settings on the right, and the Printer as a raised round home button in
/// the middle. The bar insets the content via safeAreaInset, so nothing hides behind it.
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
        .safeAreaInset(edge: .bottom) { BottomBar(tab: $nav.tab) }
    }
}

/// The bar itself: a carbon slab with a top hairline, a left and right tab, and the
/// round printer button lifted into the middle.
private struct BottomBar: View {
    @Binding var tab: RootTab

    var body: some View {
        ZStack(alignment: .top) {
            // the slab
            HStack(spacing: 0) {
                tabItem(.sheets, "square.stack", "Sheets")
                Spacer(minLength: 0)
                Color.clear.frame(width: 72)   // room for the raised home button
                Spacer(minLength: 0)
                tabItem(.settings, "gearshape", "Settings")
            }
            .padding(.horizontal, 26)
            .padding(.top, 14)
            .frame(maxWidth: .infinity)
            .frame(height: 62)
            .background(
                Ui.surface
                    .overlay(Rectangle().fill(Ui.border).frame(height: 1), alignment: .top)
                    .ignoresSafeArea(edges: .bottom)
            )

            // the printer home — a raised round button, its ring glowing when active
            printerButton
                .offset(y: -22)
        }
    }

    private func tabItem(_ t: RootTab, _ icon: String, _ label: String) -> some View {
        let active = tab == t
        return Button {
            withAnimation(.easeOut(duration: 0.15)) { tab = t }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 21, weight: active ? .semibold : .regular))
                Text(label).font(Ui.mono(10))
            }
            .foregroundColor(active ? Ui.accent : Ui.text3)
            .frame(width: 64)
        }
    }

    private var printerButton: some View {
        let active = tab == .printer
        return Button {
            withAnimation(.easeOut(duration: 0.15)) { tab = .printer }
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [Ui.surface2, Ui.bg], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .overlay(Circle().stroke(active ? Ui.accent : Ui.borderStrong, lineWidth: active ? 2 : 1))
                        .frame(width: 60, height: 60)
                        .shadow(color: active ? Ui.accent.opacity(0.4) : .black.opacity(0.4), radius: active ? 14 : 8, y: 3)
                    RingMark(size: 30)
                        .opacity(active ? 1 : 0.7)
                }
                Text("Printer").font(Ui.mono(10)).foregroundColor(active ? Ui.accent : Ui.text3)
            }
        }
    }
}
