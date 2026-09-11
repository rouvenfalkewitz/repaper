import SwiftUI
import RePaperKit

/// The Sheets tab: the e-paper this phone can print on — the library, adding by QR or
/// pasted link, and the NFC-tap setup. (The sheet-link tech strip lives at the foot.)
struct SheetsView: View {
    @EnvironmentObject var sheets: SheetStore
    @State private var addLink = ""
    @State private var addNote = ""
    @State private var adding = false
    @State private var showScanner = false
    @State private var showPaste = false
    @State private var nfc = NfcReader()
    @State private var actionSheet: Sheet?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                TabHeader(title: "Sheets")

                VStack(spacing: 0) {
                    ForEach(sheets.sheets) { s in
                        sheetRow(s)
                        RowDivider()
                    }
                    if sheets.sheets.isEmpty {
                        Text("No sheets yet — scan the QR on a sheet to add your first.")
                            .font(Ui.body(13)).foregroundColor(Ui.text3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        RowDivider()
                    }
                    addRows
                    RowDivider()
                    TechRow(icon: "dot.radiowaves.left.and.right", title: "Sheet link",
                            sub: "How pages reach the paper", chips: ["OpenDisplay BLE", "NFC tags"])
                }
                .card()
            }
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 28)
        }
        .background(Ui.bg.ignoresSafeArea())
        .confirmationDialog(actionSheet?.name ?? "", isPresented: .init(
            get: { actionSheet != nil }, set: { if !$0 { actionSheet = nil } }), titleVisibility: .visible) {
            if actionSheet?.landingUrl != nil {
                Button("Re-program the NFC tag") {
                    let s = actionSheet; actionSheet = nil
                    if let s { Task { await reprogramTag(s) } }
                }
            }
            if NfcReader.available {
                Button(actionSheet?.tagUid == nil ? "Set up tapping" : "Re-learn the tag") {
                    let s = actionSheet; actionSheet = nil
                    if let s { learnTag(for: s.id) }
                }
            }
            Button("Remove from this device", role: .destructive) {
                if let s = actionSheet { sheets.remove(s.id) }; actionSheet = nil
            }
            Button("Cancel", role: .cancel) { actionSheet = nil }
        } message: {
            Text("Removing only forgets the sheet here — it keeps what it currently shows.")
        }
        .sheet(isPresented: $showScanner) { scannerSheet }
    }

    /// A sheet as a sheet — address + inks up top, the panel at its real aspect ratio
    /// in a carbon bezel, the name below.
    private func sheetRow(_ s: Sheet) -> some View {
        VStack(spacing: 0) {
            HStack {
                Text(s.address.uppercased())
                    .font(Ui.mono(11)).kerning(0.8).foregroundColor(Ui.text2)
                Spacer()
                PalDots(palette: s.model.palette)
            }
            let pw: CGFloat = 190
            let ph = min(max(pw * CGFloat(s.model.height) / CGFloat(s.model.width), 28), 190)
            ZStack {
                RoundedRectangle(cornerRadius: 4).fill(Ui.epaperPanel)
                Text("\(s.model.width)×\(s.model.height)").font(Ui.mono(11)).foregroundColor(Ui.ink)
            }
            .frame(width: pw, height: ph)
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 10).fill(Ui.epaperBezel))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Ui.borderStrong, lineWidth: 1))
            .padding(.top, 10).padding(.bottom, 8)
            Text(s.name).font(Ui.body(16, weight: 600)).foregroundColor(Ui.text)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("hold for options").font(Ui.mono(10)).foregroundColor(Ui.text3)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 2)
        }
        .contentShape(Rectangle())
        .onLongPressGesture { actionSheet = s }
    }

    /// Adding a sheet: camera first, pasting tucked away.
    private var addRows: some View {
        VStack(spacing: 10) {
            if QrScanView.available {
                UiButton(label: "Scan QR code", primary: true) { showScanner = true }.disabled(adding)
            }
            if !QrScanView.available || showPaste {
                HStack(spacing: 8) {
                    TextField("https://…", text: $addLink)
                        .font(Ui.mono(12)).foregroundColor(Ui.text)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(.horizontal, 10).padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Ui.bg))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Ui.borderStrong, lineWidth: 1))
                    Button { Task { await addSheet() } } label: {
                        Text("Add").font(Ui.body(14, weight: 700)).foregroundColor(Ui.onAccent)
                            .padding(.horizontal, 16).padding(.vertical, 10)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Ui.accent))
                    }.disabled(adding)
                }
            }
            if adding || !addNote.isEmpty {
                HStack(spacing: 8) {
                    if adding { ProgressView().tint(Ui.accent).scaleEffect(0.8) }
                    Text(adding && addNote.isEmpty ? "Reading the sheet — keep it nearby…" : addNote)
                        .font(Ui.body(12)).foregroundColor(adding ? Ui.text2 : Ui.amber)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !showPaste && QrScanView.available {
                Button { showPaste = true } label: {
                    Text("or paste the sheet's link").font(Ui.mono(11)).foregroundColor(Ui.text3).underline()
                }
            }
        }
    }

    private var scannerSheet: some View {
        ZStack(alignment: .bottom) {
            QrScanView { link in showScanner = false; addLink = link; Task { await addSheet() } }
                .ignoresSafeArea()
            Text("Point the camera at the sheet's QR code")
                .font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Capsule().fill(Ui.bg.opacity(0.85)))
                .padding(.bottom, 28)
        }
    }

    private func addSheet() async {
        let link = addLink.trimmingCharacters(in: .whitespaces)
        guard !link.isEmpty else { addNote = "Paste the sheet's link first."; return }
        let landing: Landing
        do { landing = try Landing.parse(link) }
        catch { addNote = "That doesn't look like a RePaper sheet link."; return }
        adding = true
        addNote = "Looking for \(landing.name) nearby — wake the sheet…"
        do {
            _ = try await SheetOps.describeAndRegister(landing, link: link)
            addLink = ""; showPaste = false
            switch SheetOps.lastTagProgrammed {
            case .some(true): addNote = "Added \(landing.name) — it prints, and you can tap it too."
            case .some(false):
                if NfcReader.available {
                    addNote = "Added \(landing.name). One more step for tap-to-print…"
                    learnTag(for: landing.name)
                } else {
                    addNote = "Added \(landing.name) — it prints fine. Choose it from the list to print."
                }
            case .none: addNote = ""
            }
        } catch { addNote = error.localizedDescription }
        adding = false
    }

    private func learnTag(for sheetId: String) {
        nfc.scan(prompt: "Hold the sheet to the top edge of the iPhone to set up tapping.") { read in
            guard let read, !read.uid.isEmpty else {
                addNote = "Added — it prints fine. (Tapping wasn't set up; you can add it later from the sheet's options.)"
                return
            }
            SheetStore.shared.setTagUid(sheetId, read.uid)
            DiagLog.log("tag fingerprint learned for \(sheetId): \(read.uid)")
            addNote = "All set — tapping this sheet prints on it."
        }
    }

    private func reprogramTag(_ sheet: Sheet) async {
        adding = true
        addNote = "Waking \(sheet.name) to re-program its tag…"
        do {
            try await SheetOps.programTag(sheet)
            addNote = "Tag programmed — tapping \(sheet.name) works now."
        } catch { addNote = error.localizedDescription }
        adding = false
    }
}
