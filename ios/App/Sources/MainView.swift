import SwiftUI

/// The main screen is the printer, like the Dock's page: the light ring front and centre,
/// speaking the LED language. Sheets live in Settings; content arrives via the share
/// sheet once the share extension lands (the next build).
struct MainView: View {
    @EnvironmentObject var cloud: CloudAgent
    @EnvironmentObject var sheets: SheetStore
    @State private var showSettings = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HStack(spacing: 9) {
                    RingMark(size: 24)
                    Text("RePaper Go").font(Ui.display(20, weight: 700, width: 112)).foregroundColor(Ui.text)
                    Spacer()
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: 18)).foregroundColor(Ui.text2)
                            .padding(8)
                    }
                }

                // the ring in its box — the device outcut, exactly like the Dock's page
                RingBox { RingView(led: led) }
                    .padding(.top, 26)
                pill.padding(.top, 18)
                Text(title)
                    .font(Ui.display(22, weight: 700)).foregroundColor(Ui.text)
                    .padding(.top, 8)
                Text(subtitle)
                    .font(Ui.body(14)).foregroundColor(Ui.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16).padding(.top, 4)
                if led == .ready {
                    HStack(spacing: 6) {
                        Chip(text: "Share to print")
                        Chip(text: "OpenDisplay BLE")
                    }.padding(.top, 12)
                }
            }
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 28)
        }
        .background(Ui.bg.ignoresSafeArea())
        .fullScreenCover(isPresented: $showSettings) { SettingsView() }
        .onAppear { cloud.start() }
    }

    // ── the LED language, app edition ────────────────────────────────────────

    private var led: RingView.Led { sheets.sheets.isEmpty ? .setup : .ready }

    private var pill: Pill {
        switch led {
        case .setup: return Pill(text: "Setup", fg: Ui.blue, bg: Ui.blueTint, blinkMs: 500)
        default: return Pill(text: "Ready", fg: Ui.accent, bg: Ui.accentTint)
        }
    }

    private var title: String { led == .setup ? "Set me up" : "Ready to print" }

    private var subtitle: String {
        led == .setup
            ? "Add your first sheet in Settings — the gear, top right."
            : "Share a page or photo to RePaper Go — the share extension arrives with the next build."
    }
}
