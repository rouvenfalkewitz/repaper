import SwiftUI
import RePaperKit

/// Settings — sheets and account plumbing live here; the main screen stays the printer.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var cloud: CloudAgent
    @EnvironmentObject var sheets: SheetStore
    @State private var printerName = Prefs.printerName
    @State private var cycle = Prefs.cycleSheets
    @State private var addLink = ""
    @State private var addNote = ""
    @State private var adding = false
    @State private var showScanner = false
    @State private var showPaste = false
    @State private var nfcWriter = NfcWriter()
    @State private var confirmSignOut = false
    @State private var signOutNote = ""
    @State private var actionSheet: Sheet?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HStack(spacing: 6) {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 18, weight: .semibold)).foregroundColor(Ui.text2)
                            .padding(8)
                    }
                    Text("Settings").font(Ui.display(20, weight: 700, width: 112)).foregroundColor(Ui.text)
                    Spacer()
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 18)).foregroundColor(Ui.text3)
                        .padding(8)
                }

                // the printer half: how this device behaves as a printer
                SectionHeader(text: "Printer")
                VStack(spacing: 0) {
                    HStack(spacing: 12) {
                        settingIcon("printer.fill")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Printer name").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                            TextField("RePaper Go", text: $printerName)
                                .font(Ui.body(14)).foregroundColor(Ui.text2)
                                .onSubmit { Prefs.printerName = printerName; printerName = Prefs.printerName }
                        }
                    }
                    divider
                    HStack(spacing: 12) {
                        settingIcon("arrow.triangle.2.circlepath")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Cycle through sheets").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                            Text("With several sheets, jobs print on each in turn.")
                                .font(Ui.body(12)).foregroundColor(Ui.text3)
                        }
                        Spacer()
                        Toggle("", isOn: $cycle).labelsHidden().tint(Ui.accent)
                            .onChange(of: cycle) { Prefs.cycleSheets = $0 }
                    }
                    divider
                    techRow(icon: "square.and.arrow.up", title: "Intake",
                            sub: "How pages reach this printer", chips: ["Share to print"])
                }
                .card()

                // the sheets half: the paper this printer can put ink on
                SectionHeader(text: "Sheets")
                ForEach(sheets.sheets) { s in
                    sheetCard(s)
                }
                if sheets.sheets.isEmpty {
                    Text("No sheets yet — add the first one below.")
                        .font(Ui.body(13)).foregroundColor(Ui.text3)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 8)
                }
                addCard
                techRow(icon: "dot.radiowaves.left.and.right", title: "Sheet link",
                        sub: "How pages reach the paper", chips: ["OpenDisplay BLE", "NFC tags"])
                    .card().padding(.top, 8)

                SectionHeader(text: "RePaper Cloud")
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        Circle().fill(cloud.state == "online" ? Ui.accent : Ui.amber).frame(width: 8, height: 8)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(cloud.state == "online" ? "Connected" : "Connecting…")
                                .font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                            Text(cloud.org.map { "Managed in \($0)" } ?? "RePaper Cloud")
                                .font(Ui.body(12)).foregroundColor(Ui.text3)
                        }
                        Spacer()
                        Text("v\(GO_IOS_VERSION)").font(Ui.mono(11)).foregroundColor(Ui.text3)
                    }
                    divider
                    Button { confirmSignOut = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                                .font(.system(size: 15, weight: .medium)).foregroundColor(Ui.red)
                                .frame(width: 34, height: 34)
                                .background(RoundedRectangle(cornerRadius: 9).fill(Ui.redTint))
                                .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.border, lineWidth: 1))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Sign out").font(Ui.body(14, weight: 600)).foregroundColor(Ui.red)
                                Text("Removes this device from the fleet — sheets stay.")
                                    .font(Ui.body(12)).foregroundColor(Ui.text3)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12, weight: .semibold)).foregroundColor(Ui.text3)
                        }
                    }
                }
                .card()
                if !signOutNote.isEmpty {
                    Text(signOutNote).font(Ui.body(12)).foregroundColor(Ui.amber)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 4)
                }
            }
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 28)
        }
        .background(Ui.bg.ignoresSafeArea())
        .onDisappear { Prefs.printerName = printerName }
        .alert("Sign out of RePaper Go?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { Task { await signOut() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This device is removed from \(cloud.org ?? "your fleet"). Your sheets stay on this device; signing in again brings it right back.")
        }
        .confirmationDialog(actionSheet?.name ?? "", isPresented: .init(
            get: { actionSheet != nil }, set: { if !$0 { actionSheet = nil } }), titleVisibility: .visible) {
            if NfcReader.available, let link = actionSheet?.landingUrl {
                Button("Program the NFC tag") { actionSheet = nil; programTag(link) }
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

    private var divider: some View {
        Rectangle().fill(Ui.border).frame(height: 1)
            .padding(.vertical, 10)
    }

    private func settingIcon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 15, weight: .medium)).foregroundColor(Ui.text2)
            .frame(width: 34, height: 34)
            .background(RoundedRectangle(cornerRadius: 9).fill(Ui.surface2))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.border, lineWidth: 1))
    }

    /// A sheet as a sheet — the Dock's card anatomy: address + inks up top,
    /// the panel at its real aspect ratio in a carbon bezel, the name below.
    private func sheetCard(_ s: Sheet) -> some View {
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
                Text("\(s.model.width)×\(s.model.height)")
                    .font(Ui.mono(11)).foregroundColor(Ui.ink)
            }
            .frame(width: pw, height: ph)
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 10).fill(Ui.epaperBezel))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Ui.borderStrong, lineWidth: 1))
            .padding(.top, 10).padding(.bottom, 8)
            Text(s.name)
                .font(Ui.body(16, weight: 600)).foregroundColor(Ui.text)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("hold for options")
                .font(Ui.mono(10)).foregroundColor(Ui.text3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 2)
        }
        .card().padding(.bottom, 8)
        .onLongPressGesture { actionSheet = s }
    }

    /// The slim technology strip at the bottom of a section's card.
    private func techRow(icon: String, title: String, sub: String, chips: [String]) -> some View {
        HStack(spacing: 12) {
            settingIcon(icon)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                Text(sub).font(Ui.body(12)).foregroundColor(Ui.text3)
            }
            Spacer()
            HStack(spacing: 5) {
                ForEach(chips, id: \.self) { Chip(text: $0) }
            }
        }
    }

    /// Adding a sheet: camera first, pasting tucked away. (No add-by-NFC-tap:
    /// sheets can ship with an EMPTY tag — the QR is the ground truth, and the
    /// add flow programs the tag right afterwards so tapping works from then on.)
    private var addCard: some View {
        VStack(spacing: 10) {
            if QrScanView.available {
                UiButton(label: "Scan QR code", primary: true) { showScanner = true }
                    .disabled(adding)
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
                    Text("or paste the sheet's link")
                        .font(Ui.mono(11)).foregroundColor(Ui.text3).underline()
                }
            }
        }
        .card()
    }

    private var scannerSheet: some View {
        ZStack(alignment: .bottom) {
            QrScanView { link in
                showScanner = false
                addLink = link
                Task { await addSheet() }
            }
            .ignoresSafeArea()
            Text("Point the camera at the sheet's QR code")
                .font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Capsule().fill(Ui.bg.opacity(0.85)))
                .padding(.bottom, 28)
        }
    }

    // ── adding ───────────────────────────────────────────────────────────────

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
            addNote = ""; addLink = ""; showPaste = false
            adding = false
            // some sheets ship with an empty tag: program it now so tap-to-print
            // always works (the finding of 10 Sep — applies to every RePaper variant)
            if NfcReader.available { programTag(link) }
        } catch {
            addNote = error.localizedDescription
            adding = false
        }
    }

    private func programTag(_ link: String) {
        nfcWriter.write(link, prompt: "Hold the sheet to the top edge to program its tag.") { ok in
            addNote = ok ? "" : "Tag not programmed — hold the sheet for options to retry."
            if ok { DiagLog.log("nfc tag programmed") }
        }
    }

    // ── sign out ─────────────────────────────────────────────────────────────

    /// Sign out = remove this device from the fleet server-side, then gate locally.
    /// The next sign-in claims it right back (the identity is kept).
    private func signOut() async {
        signOutNote = "Signing out…"
        do {
            var req = URLRequest(url: URL(string: "\(Prefs.cloudBase)/api/device/unclaim")!)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: [
                "id": Identity.shared.deviceId, "secret": Identity.shared.secret])
            let (data, _) = try await URLSession.shared.data(for: req)
            let ok = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["ok"] as? Bool ?? false
            guard ok else {
                signOutNote = "couldn't reach the cloud — check the connection and try again"
                return
            }
        } catch {
            signOutNote = "couldn't reach the cloud — check the connection and try again"
            return
        }
        DiagLog.log("signed out — removed from fleet")
        Prefs.claimed = false
        cloud.claimed = false      // the root router swaps to the sign-in gate
        dismiss()
    }
}
