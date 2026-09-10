import SwiftUI
import RePaperKit

/// The main screen is the printer, like the Dock's page: the light ring front and centre,
/// speaking the LED language, with the job that's waiting below. Sheets live in Settings;
/// content arrives via the share sheet ("share to print").
struct MainView: View {
    @EnvironmentObject var cloud: CloudAgent
    @EnvironmentObject var sheets: SheetStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var jobs: [URL] = []
    @State private var busy = false
    @State private var flash: RingView.Led?   // DONE/ERR held briefly, then back to the state machine
    @State private var phase = ""             // narration while printing
    @State private var pickFor: URL?
    @State private var autoTried: Set<String> = []   // one-sheet auto-print: one attempt per job
    @State private var errorNote = ""
    @State private var nfc = NfcReader()
    @State private var info = ""                     // transient tap-result message
    @State private var pendingAdd: Landing?          // unknown sheet tapped → offer to add

    var body: some View {
        GeometryReader { geo in
        ScrollView {
            VStack(spacing: 0) {
                HStack {
                    BrandLockup(height: 26)
                    Spacer()
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: 18)).foregroundColor(Ui.text2)
                            .padding(8)
                    }
                }

                // the hero sits at a FIXED offset (per device, never per state): every
                // slot below has a reserved height, so the ring never moves when the
                // state, texts or buttons change.
                Spacer().frame(height: max(24, geo.size.height * 0.055))

                // the ring in its box — the device outcut, exactly like the Dock's page
                RingBox { RingView(led: led) }
                pill
                    .frame(height: 26)
                    .padding(.top, 18)
                // the state as a readout: big condensed display caps, tracked, in the
                // state's own colour — it reads as a live status, not a heading
                Text(title.uppercased())
                    .font(Ui.display(26, weight: 800, width: 94)).kerning(0.5)
                    .foregroundColor(stateColor)
                    .frame(height: 34)
                    .padding(.top, 6)
                Text(subtitle)
                    .font(Ui.body(14)).foregroundColor(Ui.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16).padding(.top, 4)
                    .frame(height: 58, alignment: .top)

                // action slot — reserved even when empty, so the layout stays put.
                // tap-to-print appears only when a job actually needs a sheet CHOICE
                // (several sheets, cycling off); choosing from the list is the fallback.
                VStack(spacing: 8) {
                    if led == .wait, sheets.sheets.count > 1, !Prefs.cycleSheets {
                        if NfcReader.available {
                            nfcTapButton
                            Button { pickFor = jobs.first } label: {
                                Text("or choose from the list")
                                    .font(Ui.mono(11)).foregroundColor(Ui.text3).underline()
                            }
                        } else {
                            UiButton(label: "Choose the sheet", primary: true) { pickFor = jobs.first }
                        }
                    }
                }
                .frame(height: 84, alignment: .top)
                .padding(.top, 10)

                if !jobs.isEmpty {
                    SectionHeader(text: "Waiting to print")
                    ForEach(jobs, id: \.self) { job in
                        JobCard(job: job)
                            .onTapGesture { pickFor = job }
                            .contextMenu {
                                Button(role: .destructive) {
                                    try? FileManager.default.removeItem(at: job); refresh()
                                } label: { Label("Discard", systemImage: "trash") }
                            }
                    }
                } else if led == .ready {
                    howToPrint
                } else if led == .setup {
                    setupSteps
                }
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 20).padding(.top, 16)
            .frame(minHeight: geo.size.height - 40, alignment: .top)
        }
        }
        .background(Ui.bg.ignoresSafeArea())
        .fullScreenCover(isPresented: $showSettings, onDismiss: { refresh() }) { SettingsView() }
        .confirmationDialog("Print on which sheet?", isPresented: .init(
            get: { pickFor != nil }, set: { if !$0 { pickFor = nil } }), titleVisibility: .visible) {
            ForEach(sheets.sheets) { s in
                Button(s.name) { if let j = pickFor { pickFor = nil; print(job: j, on: s) } }
            }
            Button("Cancel", role: .cancel) { pickFor = nil }
        }
        .alert(info, isPresented: .init(get: { !info.isEmpty }, set: { if !$0 { info = "" } })) {
            Button("OK", role: .cancel) { info = "" }
        }
        .onReceive(NotificationCenter.default.publisher(for: .mirrorJobArrived)) { _ in refresh() }
        .onAppear {
            cloud.start(); refresh()
            // visual-test hook: `simctl launch … --open-settings` jumps straight there
            if ProcessInfo.processInfo.arguments.contains("--open-settings") { showSettings = true }
        }
        .onChange(of: scenePhase) { p in if p == .active { refresh() } }   // a share may have spooled a job
    }

    /// The tap-to-print button: an accent capsule with the contactless glyph,
    /// breathing gently — the invitation to touch paper with the phone.
    private var nfcTapButton: some View {
        Button { tapSheet() } label: {
            HStack(spacing: 10) {
                TimelineView(.animation(minimumInterval: 0.08)) { tl in
                    Image(systemName: "wave.3.right")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(Ui.onAccent)
                        .opacity(0.55 + 0.45 * (0.5 + 0.5 * sin(tl.date.timeIntervalSince1970 * 2.2)))
                }
                Text("Tap the sheet to print")
                    .font(Ui.body(15, weight: 700))
                    .foregroundColor(Ui.onAccent)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(Capsule().fill(Ui.accent))
            .shadow(color: Ui.accent.opacity(0.35), radius: 12, y: 2)
        }
    }

    // ── tap-to-print: the sheet that touches the phone IS the sheet choice ──

    private func tapSheet() {
        nfc.scan(prompt: "Hold the sheet to the top edge of the iPhone.") { read in
            guard let read else { return }   // cancelled or unreadable tag
            onSheetTap(read)
        }
    }

    private func onSheetTap(_ read: TagRead) {
        // a sheet whose tag carries a landing link resolves the normal way; a sheet
        // whose tag we fingerprinted (no link) matches by its hardware serial
        let known: Sheet? = read.uri.flatMap { try? Landing.parse($0) }.flatMap { sheets.find($0) }
            ?? sheets.findByUid(read.uid)
        DiagLog.log("nfc tap: uid \(read.uid) → \(known?.name ?? "unknown")")
        guard let known else {
            info = "This sheet isn't set up for tapping on this iPhone yet — add it in Settings, or hold it there to set up tapping."
            return
        }
        guard let job = jobs.first else {
            info = "That's \(known.name) — nothing waiting to print."; return
        }
        print(job: job, on: known)
    }

    /// How printing works, told in pictures: share → the app → e-paper.
    /// Numbered steps under a "HOW TO USE" header (Rouven, 10 Sep).
    private var howToPrint: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("HOW TO USE")
                .font(Ui.display(11, weight: 700, width: 112)).kerning(1.6)
                .foregroundColor(Ui.text3)
            HStack(spacing: 0) {
                howTile(step: 1, caption: "Share") {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 22, weight: .medium))
                        .foregroundColor(Ui.text)
                        .offset(y: -2)
                }
                howArrow
                howTile(step: 2, caption: "RePaper Go") {
                    // the OFFICIAL app icon mark — the same thing people tap on the homescreen
                    Image("Mark")
                        .resizable().scaledToFit()
                        .frame(width: 40, height: 40)
                }
                howArrow
                howTile(step: 3, caption: "On paper") { miniSheet }
            }
        }
        .padding(.vertical, 16).padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 14).fill(Ui.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Ui.border, lineWidth: 1))
        .padding(.top, 18)
    }

    /// First-run steps, same numbered-tile language as the how-to card — but in the
    /// setup-blue accent so it belongs to the setup state.
    private var setupSteps: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("GETTING STARTED")
                .font(Ui.display(11, weight: 700, width: 112)).kerning(1.6)
                .foregroundColor(Ui.text3)
            HStack(spacing: 0) {
                howTile(step: 1, caption: "Settings", accent: Ui.blue, tint: Ui.blueTint) {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 22)).foregroundColor(Ui.text)
                }
                howArrow
                howTile(step: 2, caption: "Scan QR", accent: Ui.blue, tint: Ui.blueTint) {
                    Image(systemName: "qrcode.viewfinder")
                        .font(.system(size: 24)).foregroundColor(Ui.blue)
                }
                howArrow
                howTile(step: 3, caption: "Add label", accent: Ui.blue, tint: Ui.blueTint) { miniSheet }
            }
        }
        .padding(.vertical, 16).padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 14).fill(Ui.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Ui.border, lineWidth: 1))
        .padding(.top, 18)
    }

    private var howArrow: some View {
        Image(systemName: "arrow.right")
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(Ui.text3)
            .frame(maxWidth: .infinity)
            .offset(y: -8)   // align with the tile centres, not the captions
    }

    /// The label itself, in miniature: carbon bezel, paper panel, an ink line and a red
    /// line — colour-neutral, so it fits both the green how-to and the blue setup cards.
    private var miniSheet: some View {
        VStack(spacing: 3) {
            RoundedRectangle(cornerRadius: 1.5).fill(Ui.ink).frame(width: 22, height: 3)
            RoundedRectangle(cornerRadius: 1.5).fill(Ui.epaperRed).frame(width: 14, height: 3)
        }
        .frame(width: 34, height: 22)
        .background(RoundedRectangle(cornerRadius: 3).fill(Ui.epaperPanel))
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 6).fill(Ui.epaperBezel))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Ui.borderStrong, lineWidth: 1))
    }

    private func howTile(step: Int, caption: String, accent: Color = Ui.accent, tint: Color = Ui.accentTint,
                         @ViewBuilder content: () -> some View) -> some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 16)
                    .fill(LinearGradient(colors: [Ui.surface2, Ui.bg], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(Ui.borderStrong, lineWidth: 1))
                content()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                // the step number: a display-face numeral in a tinted accent badge
                Text("\(step)")
                    .font(Ui.display(12, weight: 700, width: 112))
                    .foregroundColor(accent)
                    .frame(width: 20, height: 20)
                    .background(Circle().fill(tint))
                    .overlay(Circle().stroke(Ui.borderStrong, lineWidth: 1))
                    .offset(x: -6, y: -6)
            }
            .frame(width: 64, height: 64)
            Text(caption)
                .font(Ui.mono(10)).foregroundColor(Ui.text3)
        }
    }

    // ── the LED language, app edition: Printing > flash > Job waiting > Setup > Ready ──

    private var led: RingView.Led {
        if busy { return .busy }
        if let f = flash { return f }
        if !jobs.isEmpty { return .wait }
        return sheets.sheets.isEmpty ? .setup : .ready
    }

    /// The state's own colour — the status readout wears it (Ready/Printing green,
    /// Failed red, Setup blue).
    private var stateColor: Color {
        switch led {
        case .err: return Ui.red
        case .setup: return Ui.blue
        default: return Ui.text
        }
    }

    private var pill: Pill {
        switch led {
        case .ready: return Pill(text: "Ready", fg: Ui.accent, bg: Ui.accentTint)
        case .wait: return Pill(text: "Waiting for sheet", fg: Ui.accent, bg: Ui.accentTint, blinkMs: 500)
        case .busy: return Pill(text: "Printing", fg: Ui.accent, bg: Ui.accentTint, blinkMs: 160)
        case .done: return Pill(text: "Printed", fg: Ui.accent, bg: Ui.accentTint, check: true)
        case .err: return Pill(text: "Failed", fg: Ui.red, bg: Ui.redTint, blinkMs: 500)
        case .setup: return Pill(text: "Setup", fg: Ui.blue, bg: Ui.blueTint, blinkMs: 500)
        }
    }

    private var title: String {
        switch led {
        case .busy: return "Printing…"
        case .done: return "Printed"
        case .err: return "Not printed"
        case .wait: return "Job waiting"
        case .setup: return "Set me up"
        case .ready: return "Ready to print"
        }
    }

    private var subtitle: String {
        switch led {
        case .busy: return phase.isEmpty ? "Keep the sheet nearby." : phase
        case .done: return "Take a look at the sheet."
        case .err: return errorNote.isEmpty ? "Hold on — then just try again." : errorNote
        case .wait: return sheets.sheets.isEmpty ? "Add a sheet in Settings first."
                                                 : "Tap a job below and choose the sheet."
        case .setup: return ""   // the setup steps card below carries the message
        case .ready: return ""   // the how-to card below carries the message
        }
    }

    // ── jobs ─────────────────────────────────────────────────────────────────

    private func refresh() {
        jobs = JobStore.list()
        // automatic sheet choice: one sheet decides itself; with "cycle through sheets"
        // on, several take turns. One attempt each — a failure waits for a tap.
        let ids = sheets.sheets
        let auto = ids.count == 1 || (Prefs.cycleSheets && ids.count > 1)
        if !busy, flash == nil, let job = jobs.first, auto, !ids.isEmpty,
           !autoTried.contains(job.lastPathComponent) {
            autoTried.insert(job.lastPathComponent)
            let pick = ids.count == 1 ? ids[0] : ids[Prefs.cycleIx % ids.count]
            print(job: job, on: pick, advanceCycle: ids.count > 1)
        }
    }

    private func print(job: URL, on sheet: Sheet, advanceCycle: Bool = false) {
        busy = true; phase = ""
        Task {
            do {
                try await PrintFlow.printJob(job, sheet: sheet) { phase = $0 }
                try? FileManager.default.removeItem(at: job)
                if advanceCycle { Prefs.bumpCycleIx() }
                busy = false
                flashState(.done, seconds: 3)
            } catch {
                busy = false
                errorNote = error.localizedDescription
                flashState(.err, seconds: 6)
            }
        }
    }

    private func flashState(_ f: RingView.Led, seconds: Double) {
        flash = f; refresh()
        Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            flash = nil; errorNote = ""; refresh()
        }
    }
}

/// A waiting job: first-page preview in an e-paper frame + the shared file's name.
struct JobCard: View {
    let job: URL
    @State private var thumb: CGImage?

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let t = thumb {
                    Image(decorative: t, scale: 1).resizable().scaledToFit()
                } else {
                    Ui.epaperPanel
                }
            }
            .frame(width: 56, height: 40)
            .background(Ui.epaperPanel)
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 10).fill(Ui.epaperBezel))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Ui.borderStrong, lineWidth: 1))
            VStack(alignment: .leading, spacing: 3) {
                Text(JobStore.title(job))
                    .font(Ui.body(16, weight: 600)).foregroundColor(Ui.text)
                    .lineLimit(1)
                Text("tap to choose a sheet · hold to discard")
                    .font(Ui.mono(11)).foregroundColor(Ui.text3)
            }
            Spacer()
        }
        .card().padding(.top, 8)
        .task {
            thumb = await Task.detached { PrintFlow.thumb(job) }.value
        }
    }
}
