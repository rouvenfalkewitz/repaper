import SwiftUI
import RePaperKit

/// The Sheets tab: the e-paper this phone can print on. Compact device-style rows that
/// surface the real facts about each sheet — its address, size, palette and whether
/// tap-to-print is set up — with a "+" to add by QR or a pasted link.
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
            VStack(spacing: 14) {
                TabHeader(title: "Sheets") { addButton }

                if sheets.sheets.isEmpty {
                    emptyState
                } else {
                    VStack(spacing: 0) {
                        ForEach(sheets.sheets) { s in
                            sheetRow(s)
                            if s.id != sheets.sheets.last?.id { RowDivider() }
                        }
                    }
                    .card()
                }

                if showPaste { pasteCard }
                if adding || !addNote.isEmpty { statusLine }

                TechRow(icon: "dot.radiowaves.left.and.right", title: "Sheet link",
                        sub: "How pages reach the paper", chips: ["OpenDisplay BLE", "NFC tags"])
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

    /// The accent "+" that replaced the decorative ring — the primary way to add.
    private var addButton: some View {
        Button {
            if QrScanView.available { showScanner = true }
            else { withAnimation(.easeOut(duration: 0.2)) { showPaste = true } }
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 17, weight: .bold)).foregroundColor(Ui.onAccent)
                .frame(width: 36, height: 36)
                .background(Circle().fill(Ui.accent))
                .shadow(color: Ui.accent.opacity(0.35), radius: 8, y: 2)
        }
        .buttonStyle(.plain)
    }

    /// A sheet as a compact row: a small e-paper chip at its true aspect ratio, the name,
    /// a mono meta line (address · size), then the palette and a tap-ready badge.
    private func sheetRow(_ s: Sheet) -> some View {
        HStack(spacing: 14) {
            epaperChip(s)
            VStack(alignment: .leading, spacing: 6) {
                Text(s.name).font(Ui.body(16, weight: 600)).foregroundColor(Ui.text).lineLimit(1)
                HStack(spacing: 7) {
                    Text(s.address.uppercased()).font(Ui.mono(11)).kerning(0.5).foregroundColor(Ui.text3)
                    Text("·").font(Ui.mono(11)).foregroundColor(Ui.text3)
                    Text("\(s.model.width)×\(s.model.height)").font(Ui.mono(11)).foregroundColor(Ui.text3)
                }
                HStack(spacing: 8) {
                    PalDots(palette: s.model.palette)
                    if s.tagUid != nil { tapReadyChip }
                }
            }
            Spacer(minLength: 4)
            Button { actionSheet = s } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .semibold)).foregroundColor(Ui.text3)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        .onTapGesture { actionSheet = s }
    }

    /// The label in miniature — carbon bezel, blank paper panel at the real aspect ratio.
    /// Not a print preview (we don't hold one), just the shape and proportions of the sheet.
    private func epaperChip(_ s: Sheet) -> some View {
        let w: CGFloat = 52
        let h = min(max(w * CGFloat(s.model.height) / CGFloat(s.model.width), 30), 52)
        return RoundedRectangle(cornerRadius: 4).fill(Ui.epaperPanel)
            .frame(width: w, height: h)
            .padding(5)
            .background(RoundedRectangle(cornerRadius: 9).fill(Ui.epaperBezel))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.borderStrong, lineWidth: 1))
            .frame(width: 62, height: 62)   // fixed cell so rows align regardless of aspect
    }

    /// A little accent chip that says tap-to-print is set up for this sheet.
    private var tapReadyChip: some View {
        HStack(spacing: 4) {
            Image(systemName: "wave.3.right").font(.system(size: 9, weight: .bold))
            Text("TAP").font(Ui.display(9, weight: 700, width: 112)).kerning(0.8)
        }
        .foregroundColor(Ui.accent)
        .padding(.horizontal, 7).padding(.vertical, 3)
        .background(Capsule().fill(Ui.accentTint))
    }

    /// The empty state: a friendly label mark and a single clear call to action.
    private var emptyState: some View {
        VStack(spacing: 16) {
            bigLabelMark
            VStack(spacing: 6) {
                Text("No sheets yet").font(Ui.body(17, weight: 700)).foregroundColor(Ui.text)
                Text("Add your first RePaper sheet by scanning the QR code printed on it.")
                    .font(Ui.body(13)).foregroundColor(Ui.text3).multilineTextAlignment(.center)
            }
            if QrScanView.available {
                UiButton(label: "Scan QR code", primary: true) { showScanner = true }
            } else {
                UiButton(label: "Add by pasting a link", primary: true) {
                    withAnimation(.easeOut(duration: 0.2)) { showPaste = true }
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40).padding(.horizontal, 24)
        .card()
    }

    /// A larger version of the label motif, for the empty state.
    private var bigLabelMark: some View {
        VStack(spacing: 5) {
            RoundedRectangle(cornerRadius: 2).fill(Ui.ink).frame(width: 42, height: 5)
            RoundedRectangle(cornerRadius: 2).fill(Ui.epaperRed).frame(width: 26, height: 5)
        }
        .frame(width: 66, height: 44)
        .background(RoundedRectangle(cornerRadius: 6).fill(Ui.epaperPanel))
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 12).fill(Ui.epaperBezel))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Ui.borderStrong, lineWidth: 1))
    }

    /// Pasting a link — revealed by "+" when the camera isn't available (or on demand).
    private var pasteCard: some View {
        VStack(spacing: 10) {
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
            Text("Paste the link from a sheet's QR code.")
                .font(Ui.body(12)).foregroundColor(Ui.text3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .card()
    }

    private var statusLine: some View {
        HStack(spacing: 8) {
            if adding { ProgressView().tint(Ui.accent).scaleEffect(0.8) }
            Text(adding && addNote.isEmpty ? "Reading the sheet — keep it nearby…" : addNote)
                .font(Ui.body(12)).foregroundColor(adding ? Ui.text2 : Ui.amber)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
