import SwiftUI

/// The gate: a RePaper Go belongs to a RePaper account. Signing in claims this phone into
/// your fleet automatically — no claim codes. The footer line is the on-prem entry point.
struct AuthView: View {
    @EnvironmentObject var cloud: CloudAgent
    @State private var email = ""
    @State private var password = ""
    @State private var code = ""
    @State private var showCode = false
    @State private var note = ""
    @State private var noteColor = Ui.amber
    @State private var consent: (org: String, admin: Bool)?
    @State private var showCloudSheet = false
    @State private var cloudUrl = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                BrandLockup(height: 40)
                    .padding(.top, 48)

                Text("Sign in with your RePaper account — this \(deviceWord) joins your fleet automatically.")
                    .font(Ui.body(14)).foregroundColor(Ui.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8).padding(.top, 10).padding(.bottom, 18)

                field("Email", text: $email, keyboard: .emailAddress, content: .username)
                field("Password", text: $password, secure: true, content: .password).padding(.top, 10)
                if showCode {
                    field("Code from your authenticator app", text: $code, keyboard: .numberPad, content: .oneTimeCode).padding(.top, 10)
                }

                if !note.isEmpty {
                    Text(note).font(Ui.body(13)).foregroundColor(noteColor)
                        .multilineTextAlignment(.center).padding(.top, 10)
                }

                UiButton(label: "Sign in", primary: true) { Task { await signIn() } }.padding(.top, 16)
                UiButton(label: "Create account", primary: false) {
                    if let url = URL(string: "\(Prefs.cloudBase)/register") { UIApplication.shared.open(url) }
                }.padding(.top, 8)

                // the footer line doubles as the on-prem entry point: tapping the server name
                // opens the reconfigure dialog — invisible to everyone who doesn't need it
                Text(cloudLabel)
                    .font(Ui.mono(11)).foregroundColor(Ui.text3)
                    .padding(.vertical, 10).padding(.horizontal, 16)
                    .padding(.top, 14)
                    .onTapGesture { cloudUrl = Prefs.cloudBase; showCloudSheet = true }
            }
            .padding(.horizontal, 24).padding(.bottom, 28)
        }
        .background(Ui.bg.ignoresSafeArea())
        .onAppear { cloud.start() }   // the device channel connects meanwhile, so claiming is instant
        .alert("Add this \(deviceWord) to \(consent?.org ?? "")?", isPresented: .init(
            get: { consent != nil }, set: { if !$0 { consent = nil } })) {
            Button("Add this \(deviceWord)") { let c = consent; consent = nil; Task { await activate(admin: c?.admin ?? true) } }
            Button("Not now", role: .cancel) { consent = nil; note = "Signed in — the \(deviceWord) was not added." }
        } message: {
            Text("It appears in the fleet as “\(Prefs.printerName)”."
                 + ((consent?.admin ?? true) ? "" : "\n\nAn administrator of \(consent?.org ?? "your organisation") must approve it before you can print."))
        }
        .sheet(isPresented: $showCloudSheet) { cloudSheet }
    }

    private var deviceWord: String { UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone" }

    private var cloudLabel: String {
        let base = Prefs.cloudBase
        if base == Prefs.defaultCloud { return "RePaper Cloud" }
        return base.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
    }

    /// On-prem installations point the app at their own server here.
    private var cloudSheet: some View {
        VStack(spacing: 14) {
            Text("Cloud server").font(Ui.display(18, weight: 700)).foregroundColor(Ui.text).padding(.top, 24)
            Text("Only for self-hosted RePaper Cloud installations.")
                .font(Ui.body(13)).foregroundColor(Ui.text2)
            field("https://…", text: $cloudUrl, keyboard: .URL)
            UiButton(label: "Save", primary: true) {
                Prefs.cloudBase = cloudUrl; showCloudSheet = false
            }
            UiButton(label: "Use RePaper Cloud", primary: false) {
                Prefs.cloudBase = ""; showCloudSheet = false
            }
            Spacer()
        }
        .padding(.horizontal, 24)
        .presentationDetents([.height(320)])
        .background(Ui.bg.ignoresSafeArea())
    }

    private func field(_ hint: String, text: Binding<String>, secure: Bool = false,
                       keyboard: UIKeyboardType = .default,
                       content: UITextContentType? = nil) -> some View {
        Group {
            if secure { SecureField(hint, text: text) }
            else { TextField(hint, text: text).keyboardType(keyboard).textInputAutocapitalization(.never).autocorrectionDisabled() }
        }
        .textContentType(content)
        .font(Ui.body(15)).foregroundColor(Ui.text)
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Ui.bg))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Ui.borderStrong, lineWidth: 1))
    }

    // ── the sign-in flow, mirroring Android's AuthActivity ───────────────────

    private func signIn() async {
        let em = email.trimmingCharacters(in: .whitespaces)
        if em.isEmpty || password.isEmpty { note = "Email and password, please."; return }
        noteColor = Ui.amber; note = "Signing in…"
        do {
            let base = Prefs.cloudBase
            if showCode {
                let r = try await post("\(base)/api/login/2fa", ["code": code.trimmingCharacters(in: .whitespaces)])
                guard r["ok"] as? Bool == true else { throw Err(r["error"] as? String ?? "that code didn't match") }
            } else {
                let r = try await post("\(base)/api/login", ["email": em, "password": password])
                if r["twofa"] as? Bool == true {
                    showCode = true
                    note = "Enter the code from your authenticator app."
                    return
                }
                guard r["ok"] as? Bool == true else { throw Err(r["error"] as? String ?? "sign in failed") }
            }
            // who am I → the consent dialog names the workspace this phone would join
            let me = try await get("\(base)/api/me")
            let org = (me["org"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "your fleet"
            let isAdmin = me["role"] as? String == "admin" || me["personal"] as? Bool == true
            note = ""
            consent = (org, isAdmin)
        } catch {
            note = error.localizedDescription
        }
    }

    private func activate(admin: Bool) async {
        noteColor = Ui.amber; note = "Adding this \(deviceWord) to your fleet…"
        do {
            let approved = try await claimSelf()
            Prefs.claimed = true; Prefs.approved = approved
            cloud.claimed = true; cloud.approved = approved   // the root router takes it from here
        } catch {
            note = error.localizedDescription
        }
    }

    /// The app knows its own claim code — claiming is one call once the device channel is up.
    private func claimSelf() async throws -> Bool {
        for _ in 0..<30 { if cloud.state == "online" { break }; try? await Task.sleep(nanoseconds: 500_000_000) }
        guard cloud.state == "online" else { throw Err("can't reach the cloud from this \(deviceWord) — check the connection") }
        if cloud.claimed { return cloud.approved }   // re-login on a device the fleet already knows
        var lastErr = "claiming failed"
        for _ in 0..<3 {
            let r = try await post("\(Prefs.cloudBase)/api/claim", ["code": cloud.claimCode])
            if r["ok"] as? Bool == true { return r["approved"] as? Bool ?? true }
            lastErr = r["error"] as? String ?? lastErr
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
        throw Err(lastErr)
    }

    // session cookies ride URLSession.shared's default cookie storage — they only need
    // to live through sign-in; afterwards the device channel (id+secret) carries everything

    private func get(_ url: String) async throws -> [String: Any] {
        let (data, _) = try await URLSession.shared.data(from: URL(string: url)!)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func post(_ url: String, _ body: [String: Any]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await URLSession.shared.data(for: req)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private struct Err: LocalizedError {
        let message: String
        init(_ m: String) { message = m }
        var errorDescription: String? { message }
    }
}
