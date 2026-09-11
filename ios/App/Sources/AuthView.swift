import SwiftUI

/// The gate: a RePaper Go belongs to a RePaper account. Signing in claims this phone into
/// your fleet automatically — no claim codes. Two stages: credentials, then (if the
/// account has it) a dedicated two-factor screen. The footer line is the on-prem entry point.
struct AuthView: View {
    enum Stage { case credentials, twoFactor }

    @EnvironmentObject var cloud: CloudAgent
    @State private var stage: Stage = .credentials
    @State private var email = ""
    @State private var password = ""
    @State private var code = ""
    @State private var note = ""
    @State private var noteColor = Ui.amber
    @State private var busy = false
    @State private var showCloudSheet = false
    @State private var cloudUrl = ""

    var body: some View {
        GeometryReader { geo in
        ScrollView {
            VStack(spacing: 0) {
                BrandLockup(height: 40)

                switch stage {
                case .credentials: credentials.transition(.opacity)
                case .twoFactor: twoFactor.transition(.asymmetric(insertion: .move(edge: .trailing), removal: .opacity))
                }
            }
            .padding(.horizontal, 24).padding(.vertical, 28)
            .frame(minHeight: geo.size.height - 40, alignment: .center)
            .animation(.easeInOut(duration: 0.28), value: stage)
        }
        }
        .background(Ui.bg.ignoresSafeArea())
        .onAppear {
            cloud.start()   // the device channel connects meanwhile, so claiming is instant
            if ProcessInfo.processInfo.arguments.contains("--twofa-preview") {
                email = "you@example.com"; stage = .twoFactor   // screenshot hook
            }
        }
        .sheet(isPresented: $showCloudSheet) { cloudSheet }
    }

    // ── stage 1: email + password ────────────────────────────────────────────

    private var credentials: some View {
        VStack(spacing: 0) {
            Text("Sign in to your RePaper account to print from this \(deviceWord).")
                .font(Ui.body(14)).foregroundColor(Ui.text2)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 8).padding(.top, 12).padding(.bottom, 20)

            VStack(spacing: 10) {
                AuthField(icon: "envelope.fill", hint: "Email", text: $email,
                          keyboard: .emailAddress, content: .username)
                AuthField(icon: "lock.fill", hint: "Password", text: $password,
                          secure: true, content: .password) { Task { await signIn() } }
            }

            note(where: .credentials)

            UiButton(label: "Sign in", primary: true, loading: busy) { Task { await signIn() } }
                .padding(.top, 16).disabled(busy)
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
    }

    // ── stage 2: the dedicated two-factor screen ─────────────────────────────

    private var twoFactor: some View {
        VStack(spacing: 0) {
            // shield hero — signals "this is a security step"
            ZStack {
                Circle().fill(Ui.accentTint).frame(width: 72, height: 72)
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 34)).foregroundColor(Ui.accent)
            }
            .padding(.top, 24)

            Text("TWO-STEP VERIFICATION")
                .font(Ui.display(14, weight: 800, width: 125)).kerning(0.4)
                .foregroundColor(Ui.text)
                .padding(.top, 16)
            Text("Enter the 6-digit code from your authenticator app for \(email).")
                .font(Ui.body(14)).foregroundColor(Ui.text2)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 12).padding(.top, 6)

            CodeField(code: $code, length: 6) { Task { await verifyCode() } }
                .padding(.top, 26)

            note(where: .twoFactor)

            UiButton(label: "Verify", primary: true, loading: busy) { Task { await verifyCode() } }
                .padding(.top, 18)
                .disabled(busy || code.count < 6)
            Button {
                code = ""; note = ""; stage = .credentials
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "chevron.left").font(.system(size: 12, weight: .semibold))
                    Text("Back to sign in").font(Ui.body(14, weight: 600))
                }.foregroundColor(Ui.text2)
            }
            .padding(.top, 16)
        }
    }

    @ViewBuilder private func note(where stage: Stage) -> some View {
        if self.stage == stage, !note.isEmpty {
            Text(note).font(Ui.body(13)).foregroundColor(noteColor)
                .multilineTextAlignment(.center).padding(.top, 12)
        }
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

    // ── the sign-in flow ─────────────────────────────────────────────────────

    private func signIn() async {
        let em = email.trimmingCharacters(in: .whitespaces)
        if em.isEmpty || password.isEmpty { noteColor = Ui.amber; note = "Email and password, please."; return }
        note = ""; busy = true          // the button shows a spinner; no status line
        defer { busy = false }
        do {
            let base = Prefs.cloudBase
            let r = try await post("\(base)/api/login", ["email": em, "password": password])
            if r["twofa"] as? Bool == true {
                note = ""; code = ""
                stage = .twoFactor
                return
            }
            guard r["ok"] as? Bool == true else { throw Err(r["error"] as? String ?? "sign in failed") }
            await afterAuthenticated(base)
        } catch {
            noteColor = Ui.red; note = error.localizedDescription
        }
    }

    private func verifyCode() async {
        guard code.count == 6, !busy else { return }
        note = ""; busy = true          // spinner on the Verify button carries it
        defer { busy = false }
        do {
            let base = Prefs.cloudBase
            let r = try await post("\(base)/api/login/2fa", ["code": code.trimmingCharacters(in: .whitespaces)])
            guard r["ok"] as? Bool == true else { throw Err(r["error"] as? String ?? "that code didn't match") }
            await afterAuthenticated(base)
        } catch {
            noteColor = Ui.red; note = error.localizedDescription; code = ""
        }
    }

    /// Signing in IS joining the fleet (decision A) — no repeated "add this device?"
    /// prompt. The device is claimed automatically; a member simply lands on the
    /// "Waiting for approval" screen, which explains the rest.
    private func afterAuthenticated(_ base: String) async {
        do {
            let me = try await get("\(base)/api/me")
            let isAdmin = me["role"] as? String == "admin" || me["personal"] as? Bool == true
            await activate(admin: isAdmin)
        } catch {
            noteColor = Ui.red; note = error.localizedDescription
        }
    }

    private func activate(admin: Bool) async {
        note = ""   // the button spinner is still up; the gate cross-fades in a moment
        do {
            let approved = try await claimSelf()
            Prefs.claimed = true; Prefs.approved = approved
            // set approved FIRST so flipping claimed lands straight on the right screen —
            // no one-frame flash of the pending screen for an admin going to the main view
            cloud.approved = approved
            cloud.claimed = true              // the root router takes it from here (cross-fades)
        } catch {
            noteColor = Ui.red; note = error.localizedDescription
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

/// A sign-in field with a leading glyph that lights up, and a border that turns accent
/// while focused.
private struct AuthField: View {
    let icon: String
    let hint: String
    @Binding var text: String
    var secure = false
    var keyboard: UIKeyboardType = .default
    var content: UITextContentType? = nil
    var onSubmit: (() -> Void)? = nil
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 11) {
            Image(systemName: icon).font(.system(size: 15, weight: .medium))
                .foregroundColor(focused ? Ui.accent : Ui.text3).frame(width: 20)
            Group {
                if secure { SecureField(hint, text: $text) }
                else {
                    TextField(hint, text: $text).keyboardType(keyboard)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                }
            }
            .textContentType(content)
            .font(Ui.body(15)).foregroundColor(Ui.text)
            .focused($focused)
            .onSubmit { onSubmit?() }
        }
        .padding(.horizontal, 14).padding(.vertical, 13)
        .background(RoundedRectangle(cornerRadius: 12).fill(Ui.bg))
        .overlay(RoundedRectangle(cornerRadius: 12)
            .stroke(focused ? Ui.accent : Ui.borderStrong, lineWidth: focused ? 1.5 : 1))
        .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// A segmented 6-box code entry: an invisible field captures the digits (and autofill),
/// the boxes show them, the active box glows in the accent. Auto-submits when full.
struct CodeField: View {
    @Binding var code: String
    var length = 6
    var onComplete: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            TextField("", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($focused)
                .foregroundColor(.clear).accentColor(.clear)
                .frame(width: 1, height: 1).opacity(0.01)
                .onChange(of: code) { v in
                    let digits = String(v.filter(\.isNumber).prefix(length))
                    if digits != code { code = digits }
                    if code.count == length { focused = false; onComplete() }
                }
            HStack(spacing: 9) {
                ForEach(0..<length, id: \.self) { box($0) }
            }
            .contentShape(Rectangle())
            .onTapGesture { focused = true }
        }
        .onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { focused = true } }
    }

    private func box(_ i: Int) -> some View {
        let chars = Array(code)
        let filled = i < chars.count
        let active = i == chars.count && focused
        return Text(filled ? String(chars[i]) : "")
            .font(Ui.display(24, weight: 700))
            .foregroundColor(Ui.text)
            .frame(width: 46, height: 58)
            .background(RoundedRectangle(cornerRadius: 12).fill(Ui.bg))
            .overlay(RoundedRectangle(cornerRadius: 12)
                .stroke(active ? Ui.accent : Ui.borderStrong, lineWidth: active ? 2 : 1))
            .shadow(color: active ? Ui.accent.opacity(0.3) : .clear, radius: 8)
    }
}
