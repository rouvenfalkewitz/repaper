import SwiftUI
import RePaperKit

/// Settings, Android parity: device name, sheet cycling, the sheet library, cloud state.
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
    @State private var confirmSignOut = false
    @State private var signOutNote = ""

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
                }

                SectionHeader(text: "This device")
                VStack(alignment: .leading, spacing: 6) {
                    Text("Printer name").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                    TextField("RePaper Go", text: $printerName)
                        .font(Ui.body(15)).foregroundColor(Ui.text)
                        .onSubmit { Prefs.printerName = printerName; printerName = Prefs.printerName }
                    Text("How this device appears in your fleet.")
                        .font(Ui.mono(11)).foregroundColor(Ui.text3)
                }.card()
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Cycle through sheets").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                        Text("With several sheets, print on each in turn.")
                            .font(Ui.mono(11)).foregroundColor(Ui.text3)
                    }
                    Spacer()
                    Toggle("", isOn: $cycle).labelsHidden().tint(Ui.accent)
                        .onChange(of: cycle) { Prefs.cycleSheets = $0 }
                }.card().padding(.top, 8)

                SectionHeader(text: "Sheets")
                ForEach(sheets.sheets) { s in
                    HStack(spacing: 12) {
                        // e-paper frame: carbon bezel around the panel — sheets are shown as sheets
                        RoundedRectangle(cornerRadius: 6)
                            .fill(Ui.epaperPanel)
                            .frame(width: 44, height: 30)
                            .padding(6)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Ui.epaperBezel))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Ui.borderStrong, lineWidth: 1))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(s.name).font(Ui.body(15, weight: 600)).foregroundColor(Ui.text)
                            HStack(spacing: 8) {
                                Text("\(s.model.width)×\(s.model.height)").font(Ui.mono(11)).foregroundColor(Ui.text3)
                                PalDots(palette: s.model.palette)
                            }
                        }
                        Spacer()
                    }
                    .card().padding(.top, 8)
                    .contextMenu {
                        Button(role: .destructive) { sheets.remove(s.id) } label: { Label("Remove", systemImage: "trash") }
                    }
                }
                if sheets.sheets.isEmpty {
                    Text("No sheets yet — add the first one below.")
                        .font(Ui.body(13)).foregroundColor(Ui.text3)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 4)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Add a sheet").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                    Text("Scan the QR code on the sheet — or paste its link below.")
                        .font(Ui.mono(11)).foregroundColor(Ui.text3)
                    if QrScanView.available {
                        UiButton(label: adding ? "Reading the sheet…" : "Scan QR code", primary: true) {
                            showScanner = true
                        }.disabled(adding)
                    }
                    TextField("https://…", text: $addLink)
                        .font(Ui.mono(12)).foregroundColor(Ui.text)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(.horizontal, 10).padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Ui.bg))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Ui.borderStrong, lineWidth: 1))
                    if !addNote.isEmpty {
                        Text(addNote).font(Ui.body(12)).foregroundColor(Ui.amber)
                    }
                    UiButton(label: adding ? "Reading the sheet…" : "Add sheet", primary: !QrScanView.available) {
                        Task { await addSheet() }
                    }.disabled(adding)
                }.card().padding(.top, 8)

                SectionHeader(text: "Cloud")
                HStack(spacing: 8) {
                    Circle().fill(cloud.state == "online" ? Ui.accent : Ui.amber).frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(cloud.state == "online" ? "Connected" : "Connecting…")
                            .font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                        Text(cloud.org.map { "Part of \($0)" } ?? "RePaper Cloud")
                            .font(Ui.mono(11)).foregroundColor(Ui.text3)
                    }
                    Spacer()
                    Text("v\(GO_IOS_VERSION)").font(Ui.mono(11)).foregroundColor(Ui.text3)
                }.card()
                Button { confirmSignOut = true } label: {
                    Text("Sign out")
                        .font(Ui.body(14, weight: 600)).foregroundColor(Ui.red)
                        .frame(maxWidth: .infinity)
                }
                .card().padding(.top, 8)
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
        .sheet(isPresented: $showScanner) {
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
    }

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
            let (data, resp) = try await URLSession.shared.data(for: req)
            let ok = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["ok"] as? Bool ?? false
            guard ok || (resp as? HTTPURLResponse)?.statusCode == 404 else {   // 404 = already gone
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

    private func addSheet() async {
        let link = addLink.trimmingCharacters(in: .whitespaces)
        guard !link.isEmpty else { addNote = "Paste the sheet's link first."; return }
        let landing: Landing
        do { landing = try Landing.parse(link) }
        catch { addNote = "That doesn't look like a RePaper sheet link."; return }
        adding = true; addNote = "Looking for \(landing.name) nearby — wake the sheet…"
        do {
            _ = try await SheetOps.describeAndRegister(landing)
            addNote = ""; addLink = ""
        } catch {
            addNote = error.localizedDescription
        }
        adding = false
    }
}
