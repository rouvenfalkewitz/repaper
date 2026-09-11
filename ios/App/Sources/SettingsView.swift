import SwiftUI
import RePaperKit

/// The Settings tab: how this device behaves as a printer, Print2Go, and the account.
/// (Sheets moved to their own tab.)
struct SettingsView: View {
    @EnvironmentObject var cloud: CloudAgent
    @State private var printerName = Prefs.printerName
    @State private var cycle = Prefs.cycleSheets
    @State private var confirmSignOut = false
    @State private var signOutNote = ""
    @State private var p2gOn = Prefs.print2goDock != nil
    @State private var p2gDocks: [(id: String, name: String, online: Bool, current: Bool)] = []
    @State private var p2gBusy = false
    @State private var p2gNote = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                TabHeader(title: "Settings")

                SectionHeader(text: "Printer")
                VStack(spacing: 0) {
                    HStack(spacing: 12) {
                        SettingIcon(name: "printer.fill")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Printer name").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                            TextField("RePaper Go", text: $printerName)
                                .font(Ui.body(14)).foregroundColor(Ui.text2)
                                .onSubmit { Prefs.printerName = printerName; printerName = Prefs.printerName }
                        }
                    }
                    RowDivider()
                    HStack(spacing: 12) {
                        SettingIcon(name: "arrow.triangle.2.circlepath")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Cycle through sheets").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                            Text("With several sheets, jobs print on each in turn.")
                                .font(Ui.body(12)).foregroundColor(Ui.text3)
                        }
                        Spacer()
                        Toggle("", isOn: $cycle).labelsHidden().tint(Ui.accent)
                            .onChange(of: cycle) { Prefs.cycleSheets = $0 }
                    }
                    RowDivider()
                    TechRow(icon: "square.and.arrow.up", title: "Intake",
                            sub: "How pages reach this printer", chips: ["Share to print"])
                }
                .card()

                SectionHeader(text: "Print2Go")
                print2goCard

                SectionHeader(text: "Cloud")
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
                    RowDivider()
                    Button { confirmSignOut = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                                .font(.system(size: 15, weight: .medium)).foregroundColor(Ui.red)
                                .frame(width: 34, height: 34)
                                .background(RoundedRectangle(cornerRadius: 9).fill(Ui.redTint))
                                .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.border, lineWidth: 1))
                            Text("Sign out").font(Ui.body(14, weight: 600)).foregroundColor(Ui.red)
                            Spacer()
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
    }

    /// Print2Go on the phone: a toggle + a Dock picker.
    private var print2goCard: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                SettingIcon(name: "arrow.down.doc.fill")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Print jobs from a Dock").font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                    Text("This phone prints the jobs sent to the Dock you pick, on its own sheets.")
                        .font(Ui.body(12)).foregroundColor(Ui.text3)
                }
                Spacer()
                Toggle("", isOn: $p2gOn).labelsHidden().tint(Ui.accent)
                    .onChange(of: p2gOn) { on in
                        if on { Task { await loadDocks() } } else { Task { await chooseDock(nil) } }
                    }
            }
            if p2gOn {
                RowDivider()
                if p2gBusy && p2gDocks.isEmpty {
                    HStack { ProgressView().tint(Ui.accent).scaleEffect(0.8); Text("Finding Docks…").font(Ui.body(13)).foregroundColor(Ui.text2) }
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else if p2gDocks.isEmpty {
                    Text("No Docks in your fleet have Print2Go on yet. Turn it on in a Dock's settings first.")
                        .font(Ui.body(13)).foregroundColor(Ui.text3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    ForEach(p2gDocks, id: \.id) { dock in
                        Button { Task { await chooseDock(dock.id) } } label: {
                            HStack(spacing: 10) {
                                Circle().fill(dock.online ? Ui.accent : Ui.text3).frame(width: 8, height: 8)
                                Text(dock.name).font(Ui.body(14, weight: dock.current ? 700 : 500))
                                    .foregroundColor(dock.current ? Ui.accent : Ui.text)
                                Spacer()
                                if dock.current { Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundColor(Ui.accent) }
                            }
                            .padding(.vertical, 6)
                        }
                        if dock.id != p2gDocks.last?.id { Rectangle().fill(Ui.border).frame(height: 1) }
                    }
                }
                if !p2gNote.isEmpty {
                    Text(p2gNote).font(Ui.body(12)).foregroundColor(Ui.amber)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6)
                }
            }
        }
        .card()
        .task { if p2gOn { await loadDocks() } }
    }

    private func loadDocks() async {
        p2gBusy = true
        p2gDocks = await cloud.print2goDocks()
        p2gBusy = false
        if p2gDocks.isEmpty { p2gNote = "" }
    }

    private func chooseDock(_ id: String?) async {
        p2gBusy = true
        let ok = await cloud.setMirrorFrom(id)
        p2gBusy = false
        if ok {
            Prefs.print2goDock = id == nil ? nil : (p2gDocks.first { $0.id == id }?.name ?? "a Dock")
            p2gNote = id == nil ? "" : "Jobs sent to that Dock now print here too."
            await loadDocks()
        } else {
            p2gNote = "Couldn't set that up — check the connection."
            if id == nil { p2gOn = Prefs.print2goDock != nil }
        }
    }

    /// Sign out = remove this device from the fleet server-side, then gate locally.
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
            guard ok else { signOutNote = "couldn't reach the cloud — check the connection and try again"; return }
        } catch {
            signOutNote = "couldn't reach the cloud — check the connection and try again"; return
        }
        DiagLog.log("signed out — removed from fleet")
        Prefs.claimed = false
        cloud.claimed = false      // RootView is only shown while claimed → the gate returns
    }
}
