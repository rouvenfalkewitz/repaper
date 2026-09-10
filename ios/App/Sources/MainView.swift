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
                Text(title)
                    .font(Ui.display(22, weight: 700)).foregroundColor(Ui.text)
                    .frame(height: 32)
                    .padding(.top, 6)
                Text(subtitle)
                    .font(Ui.body(14)).foregroundColor(Ui.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16).padding(.top, 4)
                    .frame(height: 58, alignment: .top)

                // action slot — reserved even when empty, so the layout stays put
                VStack(spacing: 0) {
                    if led == .ready {
                        HStack(spacing: 6) {
                            Chip(text: "Share to print")
                            Chip(text: "OpenDisplay BLE")
                        }
                    }
                    // tap-to-print: iOS reads NFC only in an explicit session, so the
                    // tap gets a button where Android listens passively
                    if NfcReader.available, led == .wait || led == .ready || led == .setup {
                        UiButton(label: jobs.isEmpty ? "Tap a sheet to add it" : "Tap the sheet to print",
                                 primary: led == .wait) { tapSheet() }
                            .padding(.top, 14)
                    }
                }
                .frame(height: NfcReader.available ? 108 : 36, alignment: .top)
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
        .alert("New sheet \(pendingAdd?.name ?? "")", isPresented: .init(
            get: { pendingAdd != nil }, set: { if !$0 { pendingAdd = nil } })) {
            Button("Add") { if let l = pendingAdd { pendingAdd = nil; addTapped(l) } }
            Button("Not now", role: .cancel) { pendingAdd = nil }
        } message: {
            Text("Add it to this iPhone\(jobs.isEmpty ? "" : " and print the waiting job on it")?")
        }
        .alert(info, isPresented: .init(get: { !info.isEmpty }, set: { if !$0 { info = "" } })) {
            Button("OK", role: .cancel) { info = "" }
        }
        .onAppear { cloud.start(); refresh() }
        .onChange(of: scenePhase) { p in if p == .active { refresh() } }   // a share may have spooled a job
    }

    // ── tap-to-print: the sheet that touches the phone IS the sheet choice ──

    private func tapSheet() {
        nfc.scan(prompt: jobs.isEmpty ? "Hold a sheet to the top edge of the iPhone to add it."
                                      : "Hold the sheet to the top edge of the iPhone.") { uri in
            guard let uri else { return }   // cancelled or unreadable tag
            onSheetTap(uri)
        }
    }

    private func onSheetTap(_ uri: String) {
        guard let landing = try? Landing.parse(uri) else {
            info = "That tag doesn't look like a RePaper sheet."; return
        }
        DiagLog.log("nfc tap: \(landing.name)")
        let job = jobs.first
        if let known = sheets.find(landing) {
            if let job { print(job: job, on: known) }
            else { info = "That's \(known.name) — nothing waiting to print." }
        } else {
            pendingAdd = landing
        }
    }

    private func addTapped(_ landing: Landing) {
        Task {
            do {
                info = ""
                _ = try await SheetOps.describeAndRegister(landing)
                refresh()
                if let job = jobs.first, let known = sheets.find(landing) {
                    print(job: job, on: known)
                } else {
                    info = "Added \(landing.name)."
                }
            } catch {
                info = error.localizedDescription
            }
        }
    }

    /// Three lines instead of a manual: how printing works on a phone.
    private var howToPrint: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("HOW TO PRINT")
                .font(Ui.display(11, weight: 700, width: 112)).kerning(1.3)
                .foregroundColor(Ui.text3)
            step(1, "Open a photo or document in any app")
            step(2, "Share it and pick “RePaper Go”")
            step(3, NfcReader.available
                 ? "Tap the sheet with your iPhone — with one sheet it prints by itself"
                 : "Choose the sheet — with one sheet it prints by itself")
        }
        .card()
        .padding(.top, 18)
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)")
                .font(Ui.mono(11)).foregroundColor(Ui.accent)
                .frame(width: 20, height: 20)
                .overlay(Circle().stroke(Ui.borderStrong, lineWidth: 1))
            Text(text)
                .font(Ui.body(13)).foregroundColor(Ui.text2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // ── the LED language, app edition: Printing > flash > Job waiting > Setup > Ready ──

    private var led: RingView.Led {
        if busy { return .busy }
        if let f = flash { return f }
        if !jobs.isEmpty { return .wait }
        return sheets.sheets.isEmpty ? .setup : .ready
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
        case .setup: return "Add your first sheet in Settings — the gear, top right."
        case .ready: return "Share a page or photo to RePaper Go from any app."
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
