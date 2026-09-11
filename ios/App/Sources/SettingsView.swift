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

                identityCard
                    .padding(.top, 14)   // match the breathing room the Sheets header gets from its "+" button

                SectionHeader(text: "Printer")
                printerCard

                SectionHeader(text: "Print2Go")
                print2goCard

                SectionHeader(text: "Account")
                signOutCard
                if !signOutNote.isEmpty {
                    Text(signOutNote).font(Ui.body(12)).foregroundColor(Ui.amber)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6)
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

    /// The device-identity hero: the brand ring, the product, the live cloud status,
    /// and the app version — echoes the printer hero on the main screen.
    private var identityCard: some View {
        let online = cloud.state == "online"
        return HStack(spacing: 14) {
            ZStack {
                Circle().fill(LinearGradient(colors: [Ui.surface2, Ui.bg],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                    .overlay(Circle().stroke(Ui.borderStrong, lineWidth: 1))
                RingMark(size: 40)
            }
            .frame(width: 54, height: 54)
            VStack(alignment: .leading, spacing: 4) {
                Text("RePaper Go").font(Ui.display(18, weight: 800, width: 112)).foregroundColor(Ui.text)
                HStack(spacing: 6) {
                    Circle().fill(online ? Ui.accent : Ui.amber).frame(width: 7, height: 7)
                        .shadow(color: (online ? Ui.accent : Ui.amber).opacity(0.7), radius: 4)
                    Text(online ? "Connected" : "Connecting…")
                        .font(Ui.body(13, weight: 600)).foregroundColor(Ui.text2)
                    if let org = cloud.org {
                        Text("· \(org)").font(Ui.body(13)).foregroundColor(Ui.text3).lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 8)
            Text("v\(GO_IOS_VERSION)").font(Ui.mono(11)).foregroundColor(Ui.text3)
                .padding(.horizontal, 9).padding(.vertical, 4)
                .background(Capsule().fill(Ui.surface2))
                .overlay(Capsule().stroke(Ui.border, lineWidth: 1))
        }
        .padding(.vertical, 4)
        .card()
    }

    /// How this device behaves as a printer.
    private var printerCard: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                SettingIcon(name: "printer.fill")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Printer name").font(Ui.body(15, weight: 600)).foregroundColor(Ui.text)
                    TextField("RePaper Go", text: $printerName)
                        .font(Ui.body(14)).foregroundColor(Ui.text2)
                        .onSubmit { Prefs.printerName = printerName; printerName = Prefs.printerName }
                }
            }
            .padding(.vertical, 4)
            RowDivider()
            HStack(spacing: 12) {
                SettingIcon(name: "arrow.triangle.2.circlepath")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Cycle through sheets").font(Ui.body(15, weight: 600)).foregroundColor(Ui.text)
                    Text("With several sheets, jobs print on each in turn.")
                        .font(Ui.body(12)).foregroundColor(Ui.text3)
                }
                Spacer()
                Toggle("", isOn: $cycle).labelsHidden().tint(Ui.accent)
                    .onChange(of: cycle) { Prefs.cycleSheets = $0 }
            }
            .padding(.vertical, 4)
            RowDivider()
            TechRow(icon: "square.and.arrow.up", title: "Intake",
                    sub: "How pages reach this printer", chips: ["Share to print"])
        }
        .card()
    }

    /// Sign out — its own destructive row at the foot of the screen.
    private var signOutCard: some View {
        Button { confirmSignOut = true } label: {
            HStack(spacing: 12) {
                Image(systemName: "rectangle.portrait.and.arrow.right")
                    .font(.system(size: 15, weight: .medium)).foregroundColor(Ui.red)
                    .frame(width: 34, height: 34)
                    .background(RoundedRectangle(cornerRadius: 9).fill(Ui.redTint))
                    .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.border, lineWidth: 1))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sign out").font(Ui.body(15, weight: 600)).foregroundColor(Ui.red)
                    Text("Removes this device from your fleet — sheets stay here.")
                        .font(Ui.body(12)).foregroundColor(Ui.text3)
                }
                Spacer()
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale(scale: 0.98))
        .card()
    }

    /// Print2Go on the phone: a toggle + a Dock picker.
    private var print2goCard: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                SettingIcon(name: "arrow.down.doc.fill")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Print jobs from a Dock").font(Ui.body(15, weight: 600)).foregroundColor(Ui.text)
                    Text("This phone prints the jobs sent to the Dock you pick, on its own sheets.")
                        .font(Ui.body(12)).foregroundColor(Ui.text3)
                }
                Spacer()
                Toggle("", isOn: $p2gOn).labelsHidden().tint(Ui.accent)
                    .onChange(of: p2gOn) { on in
                        if on { Task { await loadDocks() } } else { Task { await chooseDock(nil) } }
                    }
            }
            .padding(.vertical, 4)
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
                                    .shadow(color: dock.online ? Ui.accent.opacity(0.6) : .clear, radius: 3)
                                Text(dock.name).font(Ui.body(15, weight: dock.current ? 700 : 500))
                                    .foregroundColor(dock.current ? Ui.accent : Ui.text)
                                Text(dock.online ? "online" : "offline").font(Ui.mono(10)).foregroundColor(Ui.text3)
                                Spacer()
                                if dock.current { Image(systemName: "checkmark.circle.fill").font(.system(size: 16, weight: .bold)).foregroundColor(Ui.accent) }
                            }
                            .padding(.vertical, 8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressScale(scale: 0.98))
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
