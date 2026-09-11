import SwiftUI
import RePaperKit

/// The Sheets tab: the e-paper this phone can print on. Compact device-style rows that
/// surface the real facts about each sheet — address, size, palette, and whether NFC
/// tap-to-print is set up. Adding (with a paste fallback), per-sheet configure, and the
/// "how sheets connect" info are all our own panels that fade in, not system controls.
struct SheetsView: View {
    @EnvironmentObject var sheets: SheetStore
    @State private var addLink = ""
    @State private var addNote = ""
    @State private var adding = false
    @State private var showScanner = false
    @State private var showAdd = false        // our add panel (scan + paste fallback)
    @State private var showInfo = false        // "how sheets connect" panel
    @State private var configuring: Sheet?     // our per-sheet configure panel
    @State private var nfc = NfcReader()

    var body: some View {
        ZStack {
            ScrollView {
                VStack(spacing: 14) {
                    TabHeader(title: "Sheets") {
                        HStack(spacing: 10) {
                            headerButton("questionmark") { withAnimation(.easeOut(duration: 0.22)) { showInfo = true } }
                            headerButton("plus", accent: true) { openAdd() }
                        }
                    }

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
                }
                .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 28)
            }

            // NFC-op status (reprogram / learn from configure) rides a top banner
            if !addNote.isEmpty && !showAdd { noteBanner }
            if showInfo { infoOverlay }
            if showAdd { addOverlay }
            if let s = configuring { configureOverlay(s) }
        }
        .background(Ui.bg.ignoresSafeArea())
        .sheet(isPresented: $showScanner) { scannerSheet }
    }

    /// A lightweight toast at the top for status that isn't happening inside a panel.
    private var noteBanner: some View {
        VStack {
            HStack(spacing: 10) {
                if adding { ProgressView().tint(Ui.accent).scaleEffect(0.8) }
                Text(addNote).font(Ui.body(13, weight: 600)).foregroundColor(adding ? Ui.text : Ui.amber)
                Spacer(minLength: 8)
                if !adding {
                    Button { withAnimation(.easeOut(duration: 0.18)) { addNote = "" } } label: {
                        Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundColor(Ui.text3)
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panelBackground()
            .padding(.horizontal, 20).padding(.top, 8)
            Spacer()
        }
        .transition(.move(edge: .top).combined(with: .opacity))
        .zIndex(2)
    }

    // ── header controls ──────────────────────────────────────────────────────

    private func headerButton(_ icon: String, accent: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: accent ? 17 : 15, weight: .bold))
                .foregroundColor(accent ? Ui.onAccent : Ui.text2)
                .frame(width: 36, height: 36)
                .background(Circle().fill(accent ? Ui.accent : Ui.surface))
                .overlay(accent ? nil : Circle().stroke(Ui.border, lineWidth: 1))
                .shadow(color: accent ? Ui.accent.opacity(0.35) : .clear, radius: 8, y: 2)
        }
        .buttonStyle(.plain)
    }

    // ── a sheet row ────────────────────────────────────────────────────────────

    private func sheetRow(_ s: Sheet) -> some View {
        HStack(spacing: 14) {
            epaperChip(s)
            VStack(alignment: .leading, spacing: 6) {
                Text(s.name).font(Ui.body(16, weight: 600)).foregroundColor(Ui.text).lineLimit(1)
                HStack(spacing: 7) {
                    // the address is the sheet's id; only show it when it adds something
                    if s.address.caseInsensitiveCompare(s.name) != .orderedSame {
                        Text(s.address.uppercased()).font(Ui.mono(11)).kerning(0.5).foregroundColor(Ui.text3)
                        Text("·").font(Ui.mono(11)).foregroundColor(Ui.text3)
                    }
                    Text("\(s.model.width)×\(s.model.height)").font(Ui.mono(11)).foregroundColor(Ui.text3)
                }
                HStack(spacing: 8) {
                    PalDots(palette: s.model.palette)
                    if s.tagUid != nil { nfcBadge }
                }
            }
            Spacer(minLength: 4)
            Button { openConfigure(s) } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 15, weight: .semibold)).foregroundColor(Ui.text2)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(Ui.surface2))
                    .overlay(Circle().stroke(Ui.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        .onTapGesture { openConfigure(s) }
    }

    /// The label in miniature — carbon bezel, blank paper panel at the real aspect ratio.
    private func epaperChip(_ s: Sheet) -> some View {
        let w: CGFloat = 52
        let h = min(max(w * CGFloat(s.model.height) / CGFloat(s.model.width), 30), 52)
        return RoundedRectangle(cornerRadius: 4).fill(Ui.epaperPanel)
            .frame(width: w, height: h)
            .padding(5)
            .background(RoundedRectangle(cornerRadius: 9).fill(Ui.epaperBezel))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.borderStrong, lineWidth: 1))
            .frame(width: 62, height: 62)
    }

    /// A little accent chip: NFC tap-to-print is set up for this sheet.
    private var nfcBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: "wave.3.right").font(.system(size: 9, weight: .bold))
            Text("NFC").font(Ui.display(9, weight: 700, width: 112)).kerning(0.8)
        }
        .foregroundColor(Ui.accent)
        .padding(.horizontal, 7).padding(.vertical, 3)
        .background(Capsule().fill(Ui.accentTint))
    }

    // ── empty state ──────────────────────────────────────────────────────────

    private var emptyState: some View {
        VStack(spacing: 16) {
            bigLabelMark
            VStack(spacing: 6) {
                Text("No sheets yet").font(Ui.body(17, weight: 700)).foregroundColor(Ui.text)
                Text("Add your first RePaper sheet by scanning the QR code printed on it.")
                    .font(Ui.body(13)).foregroundColor(Ui.text3).multilineTextAlignment(.center)
            }
            UiButton(label: "Add a sheet", primary: true) { openAdd() }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40).padding(.horizontal, 24)
        .card()
    }

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

    // ── our panels (fade / slide in over a dimmed backdrop) ──────────────────────

    /// "How your sheets connect" — the old sheet-link strip, redesigned, behind "?".
    private var infoOverlay: some View {
        ZStack(alignment: .top) {
            scrim { withAnimation(.easeOut(duration: 0.18)) { showInfo = false } }
            VStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 18) {
                    Text("HOW YOUR SHEETS CONNECT")
                        .font(Ui.display(11, weight: 700, width: 112)).kerning(1.6).foregroundColor(Ui.text3)
                    infoItem(icon: "dot.radiowaves.left.and.right", title: "OpenDisplay BLE",
                             body: "Pages travel to the paper over Bluetooth — no Wi-Fi and no cloud in between.")
                    infoItem(icon: "wave.3.right", title: "NFC tags",
                             body: "Tap a sheet to the top of the phone to pick it for a waiting job — no menus.")
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .panelBackground()
                dismissButton { withAnimation(.easeOut(duration: 0.18)) { showInfo = false } }
            }
            .padding(.horizontal, 20).padding(.top, 74)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
        .zIndex(3)
    }

    private func infoItem(icon: String, title: String, body: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.system(size: 16, weight: .medium)).foregroundColor(Ui.accent)
                .frame(width: 38, height: 38)
                .background(RoundedRectangle(cornerRadius: 10).fill(Ui.accentTint))
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(Ui.body(15, weight: 700)).foregroundColor(Ui.text)
                Text(body).font(Ui.body(13)).foregroundColor(Ui.text3)
            }
        }
    }

    /// Add a sheet: scan first, with a paste fallback that's always there for when the
    /// camera can't read the code.
    private var addOverlay: some View {
        ZStack(alignment: .bottom) {
            scrim { hideAdd() }
            VStack(alignment: .leading, spacing: 16) {
                grabber
                Text("Add a sheet").font(Ui.body(18, weight: 700)).foregroundColor(Ui.text)
                if QrScanView.available {
                    UiButton(label: "Scan QR code", primary: true) { showScanner = true }.disabled(adding)
                    orDivider
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(QrScanView.available ? "Can't scan it? Paste the link instead"
                                              : "Paste the sheet's link")
                        .font(Ui.body(13, weight: 600)).foregroundColor(Ui.text2)
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
                if adding || !addNote.isEmpty { statusLine }
            }
            .padding(20).padding(.bottom, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panelBackground()
            .padding(.horizontal, 10).padding(.bottom, 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
        .zIndex(3)
    }

    /// Our own per-sheet configure panel — replaces the system action sheet, docks at the
    /// bottom, and is styled like the rest of the app.
    private func configureOverlay(_ s: Sheet) -> some View {
        ZStack(alignment: .bottom) {
            scrim { hideConfigure() }
            VStack(alignment: .leading, spacing: 16) {
                grabber
                HStack(spacing: 12) {
                    epaperChip(s)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(s.name).font(Ui.body(18, weight: 700)).foregroundColor(Ui.text)
                        Text("\(s.model.width)×\(s.model.height) · \(paletteName(s.model.palette))")
                            .font(Ui.mono(11)).foregroundColor(Ui.text3)
                        HStack(spacing: 8) { PalDots(palette: s.model.palette); if s.tagUid != nil { nfcBadge } }
                    }
                    Spacer()
                }
                RowDivider()
                VStack(spacing: 10) {
                    if s.landingUrl != nil {
                        configRow(icon: "arrow.triangle.2.circlepath", label: "Re-program the NFC tag") {
                            hideConfigure(); Task { await reprogramTag(s) }
                        }
                    }
                    if NfcReader.available {
                        configRow(icon: "wave.3.right",
                                  label: s.tagUid == nil ? "Set up tap-to-print" : "Re-learn the NFC tag") {
                            hideConfigure(); learnTag(for: s.id)
                        }
                    }
                    configRow(icon: "trash", label: "Remove from this device",
                              tint: Ui.red, iconBg: Ui.redTint) {
                        hideConfigure(); sheets.remove(s.id)
                    }
                }
                Text("Removing only forgets the sheet here — it keeps what it currently shows.")
                    .font(Ui.body(12)).foregroundColor(Ui.text3)
            }
            .padding(20).padding(.bottom, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panelBackground()
            .padding(.horizontal, 10).padding(.bottom, 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
        .zIndex(3)
    }

    private func configRow(icon: String, label: String, tint: Color = Ui.text,
                           iconBg: Color = Ui.surface2, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 15, weight: .medium)).foregroundColor(tint)
                    .frame(width: 34, height: 34)
                    .background(RoundedRectangle(cornerRadius: 9).fill(iconBg))
                    .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.border, lineWidth: 1))
                Text(label).font(Ui.body(15, weight: 600)).foregroundColor(tint)
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundColor(Ui.text3)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // shared panel furniture
    private func scrim(_ dismiss: @escaping () -> Void) -> some View {
        Color.black.opacity(0.6).ignoresSafeArea().onTapGesture(perform: dismiss).transition(.opacity)
    }
    private var grabber: some View {
        Capsule().fill(Ui.borderStrong).frame(width: 40, height: 5).frame(maxWidth: .infinity)
    }
    private var orDivider: some View {
        HStack(spacing: 10) {
            Rectangle().fill(Ui.border).frame(height: 1)
            Text("or").font(Ui.mono(11)).foregroundColor(Ui.text3)
            Rectangle().fill(Ui.border).frame(height: 1)
        }
    }
    private func dismissButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text("Got it").font(Ui.body(14, weight: 700)).foregroundColor(Ui.text2) }
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

    // ── actions ──────────────────────────────────────────────────────────────

    private func openAdd() { addNote = ""; withAnimation(.easeOut(duration: 0.22)) { showAdd = true } }
    private func hideAdd() { withAnimation(.easeOut(duration: 0.18)) { showAdd = false; addNote = "" } }
    private func openConfigure(_ s: Sheet) { withAnimation(.easeOut(duration: 0.22)) { configuring = s } }
    private func hideConfigure() { withAnimation(.easeOut(duration: 0.18)) { configuring = nil } }

    private func paletteName(_ p: String) -> String {
        switch p.uppercased() {
        case "BWR": return "black / white / red"
        case "BWY": return "black / white / yellow"
        case "BW":  return "black / white"
        default:    return p
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
            addLink = ""
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
        addNote = "Waking \(sheet.name) to re-program its tag…"   // rides the top banner
        do {
            try await SheetOps.programTag(sheet)
            addNote = "Tag programmed — tapping \(sheet.name) works now."
        } catch { addNote = error.localizedDescription }
        adding = false
    }
}

private extension View {
    /// The rounded carbon card behind a fade-in panel.
    func panelBackground() -> some View {
        background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Ui.surface))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Ui.border, lineWidth: 1))
            .shadow(color: .black.opacity(0.5), radius: 22, y: 10)
    }
}
