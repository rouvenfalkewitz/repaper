import SwiftUI

/// The claimed+approved app: three destinations on a bottom bar — Sheets on the left,
/// Settings on the right, and the Printer as a raised round home button in the middle
/// wearing the brand ring. The bar bleeds all the way to the physical bottom edge (its
/// surface fills the home-indicator band) while the labels stay above it, so it reads as
/// a real tab bar on every device. Content is inset via safeAreaInset — nothing hides.
struct RootView: View {
    @StateObject private var nav = Nav()

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Ui.bg.ignoresSafeArea()
                switch nav.tab {
                case .sheets: SheetsView()
                case .printer: MainView()
                case .settings: SettingsView()
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                BottomBar(tab: $nav.tab, bottomInset: geo.safeAreaInsets.bottom)
            }
        }
        .environmentObject(nav)
    }
}

/// The bar itself: a carbon slab with a top hairline that extends into the home-indicator
/// band, a left and right tab, and the round printer button lifted into the middle.
private struct BottomBar: View {
    @Binding var tab: RootTab
    /// The device's home-indicator inset — reserved as bottom padding so the labels sit
    /// above it while the slab's surface fills it.
    let bottomInset: CGFloat

    private let rowHeight: CGFloat = 58
    private let discSize: CGFloat = 66

    var body: some View {
        ZStack(alignment: .top) {
            // the slab — left/right tabs with a gap in the middle for the raised button
            HStack(spacing: 0) {
                tabItem(.sheets, "square.stack", "Sheets")
                Spacer(minLength: 0)
                Color.clear.frame(width: discSize + 12)   // room for the raised home button
                Spacer(minLength: 0)
                tabItem(.settings, "gearshape", "Settings")
            }
            .padding(.horizontal, 24)
            .padding(.top, 10)
            .frame(maxWidth: .infinity)
            .frame(height: rowHeight, alignment: .top)

            // the printer home — a raised carbon puck wearing the brand ring, lifted so it
            // breaks the top edge of the slab
            printerButton
                .offset(y: -discSize * 0.42)
        }
        .padding(.bottom, bottomInset)   // keep the labels clear of the home indicator
        .background(
            Ui.surface
                .overlay(Rectangle().fill(Ui.border).frame(height: 1), alignment: .top)
        )
        .ignoresSafeArea(edges: .bottom)  // the whole bar (surface included) reaches the physical bottom
    }

    private func tabItem(_ t: RootTab, _ icon: String, _ label: String) -> some View {
        let active = tab == t
        return Button {
            withAnimation(.easeOut(duration: 0.15)) { tab = t }
        } label: {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: active ? .semibold : .regular))
                Text(label).font(Ui.mono(10))
            }
            .foregroundColor(active ? Ui.accent : Ui.text3)
            .frame(width: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var printerButton: some View {
        let active = tab == .printer
        // the brand ring wants to fill most of the puck: RingMark's visible ring is only
        // ~0.586× its `size`, so scale up to land a ~42pt ring inside the 66pt disc.
        let ringSize = discSize * 0.64 / 0.586
        return Button {
            withAnimation(.easeOut(duration: 0.15)) { tab = .printer }
        } label: {
            VStack(spacing: 5) {
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [Ui.surface2, Ui.bg],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: discSize, height: discSize)
                        .overlay(Circle().stroke(Ui.borderStrong, lineWidth: 1))
                        .shadow(color: active ? Ui.accent.opacity(0.45) : .black.opacity(0.45),
                                radius: active ? 16 : 9, y: 3)
                    RingMark(size: ringSize)
                        .opacity(active ? 1 : 0.6)
                }
                Text("Printer").font(Ui.mono(10)).foregroundColor(active ? Ui.accent : Ui.text3)
            }
        }
        .buttonStyle(.plain)
    }
}
