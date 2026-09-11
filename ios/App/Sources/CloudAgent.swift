import Foundation

extension Notification.Name {
    /// The set of waiting Print2Go jobs changed — the main screen refreshes.
    static let mirrorJobArrived = Notification.Name("mirrorJobArrived")
    /// The fleet signed this device out — the app returns to its gate.
    static let signedOut = Notification.Name("signedOut")
}

/// A Print2Go job offered to this phone but not yet claimed — first to actually
/// print it wins, so we only claim (take) when the user/app starts printing.
struct MirrorPending: Identifiable, Equatable {
    let id: String       // cloud job id
    let name: String
    let from: String     // the Dock it came from
}

/// One outbound WebSocket to RePaper Cloud — the Dock's cloud.py in miniature, kind "go".
/// Printing never depends on it; the cloud sees metadata, never pages —
/// EXCEPT Print2Go jobs relayed from a Dock, which travel through by design.
@MainActor final class CloudAgent: ObservableObject {
    static let shared = CloudAgent()

    @Published var state = "off"
    @Published var claimed = Prefs.claimed
    @Published var approved = Prefs.approved
    @Published var org: String?
    @Published var pending: [MirrorPending] = []   // Print2Go jobs waiting to be claimed
    var claimCode: String { Identity.shared.claimCode }

    private var running = false
    private var task: URLSessionWebSocketTask?
    private var pushToken: String?   // APNs token from the app delegate, sent over the socket

    /// The app delegate hands us the APNs device token; we relay it to the cloud so a
    /// waiting Print2Go job can wake this phone even when the app is closed.
    func setPushToken(_ token: String) {
        pushToken = token
        Task { await sendPushToken() }
    }

    private func sendPushToken() async {
        guard let t = pushToken, state == "online" else { return }
        try? await send(["t": "push_token", "token": t, "env": PUSH_ENV])
    }

    private init() {}

    func start() {
        guard !running else { return }
        running = true; state = "connecting"
        Task { await runLoop() }
    }

    private func runLoop() async {
        var backoff: UInt64 = 2
        while running {
            var base = Prefs.cloudBase
            if base.hasPrefix("https") { base = "wss" + base.dropFirst(5) }
            else if base.hasPrefix("http") { base = "ws" + base.dropFirst(4) }
            guard let url = URL(string: base + "/ws/device") else { state = "error"; return }
            let ws = URLSession.shared.webSocketTask(with: url)
            task = ws
            ws.resume()

            var heartbeat: Task<Void, Never>?
            do {
                try await send(["t": "hello", "id": Identity.shared.deviceId, "secret": Identity.shared.secret,
                                "claim": Identity.shared.claimCode, "kind": "go", "platform": "ios", "version": GO_IOS_VERSION])
                state = "online"; backoff = 2
                await sendPushToken()   // hand over the APNs token (if we have one) each connect
                heartbeat = Task { [weak self] in
                    while !Task.isCancelled {
                        await self?.sendStatus()
                        try? await Task.sleep(nanoseconds: 300 * 1_000_000_000)
                    }
                }
                while true {
                    let msg = try await ws.receive()
                    if case .string(let s) = msg,
                       let obj = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any] {
                        await handle(obj)
                    }
                }
            } catch {
                if state == "online" { DiagLog.log("cloud link dropped: \(error.localizedDescription)") }
                state = "connecting"
            }
            heartbeat?.cancel()
            ws.cancel(with: .goingAway, reason: nil)
            try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
            backoff = min(backoff * 2, 60)
        }
    }

    private func handle(_ msg: [String: Any]) async {
        switch msg["t"] as? String {
        case "hello_ok":
            claimed = msg["claimed"] as? Bool ?? false
            approved = msg["approved"] as? Bool ?? true
            org = (msg["org"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            Prefs.claimed = claimed          // fleet removed us → the sign-in gate returns
            if claimed { Prefs.approved = approved }
        case "claimed":
            claimed = true
            approved = msg["approved"] as? Bool ?? true
            org = (msg["org"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            Prefs.claimed = true; Prefs.approved = approved
            await sendStatus()
        case "identify":
            break   // a phone has no LED ring; the app could vibrate later
        case "signed_out":
            claimed = false; Prefs.claimed = false
            NotificationCenter.default.post(name: .signedOut, object: nil)
        case "mirror_job":
            // a Dock offered a job to the pool — remember it; we only claim when we print
            if let job = msg["job"] as? [String: Any], let id = job["id"] as? String, pending.allSatisfy({ $0.id != id }) {
                pending.append(MirrorPending(id: id, name: job["name"] as? String ?? "job", from: job["from"] as? String ?? "a Dock"))
                NotificationCenter.default.post(name: .mirrorJobArrived, object: nil)
            }
        case "mirror_taken", "mirror_done":
            // another device grabbed/printed it — drop it from our waiting list
            if let job = msg["job"] as? [String: Any], let id = job["id"] as? String {
                pending.removeAll { $0.id == id }
                NotificationCenter.default.post(name: .mirrorJobArrived, object: nil)
            }
        case "diag":
            try? await send(["t": "diag", "log": DiagLog.dump()])
        default:
            break
        }
    }

    /// Claim a pending job and get its page bytes — first to call this wins it.
    /// Returns (name, ext, bytes) on success, nil if another device already grabbed it.
    func takeJob(_ id: String) async -> (String, String, Data)? {
        pending.removeAll { $0.id == id }
        guard let obj = try? await post("mirror-job/\(id)/take"), obj["ok"] as? Bool == true,
              let b64 = obj["data"] as? String, let bytes = Data(base64Encoded: b64) else {
            DiagLog.log("take \(id): lost the race or gone"); return nil
        }
        let ext = (obj["type"] as? String) == "pdf" ? "pdf" : "png"
        return (obj["name"] as? String ?? "job", ext, bytes)
    }
    func jobDone(_ id: String) async { _ = try? await post("mirror-job/\(id)/done") }
    func jobReleased(_ id: String) async { _ = try? await post("mirror-job/\(id)/release") }

    /// The org's Print2Go Docks this phone can print from.
    func print2goDocks() async -> [(id: String, name: String, online: Bool, current: Bool)] {
        guard let obj = try? await post("print2go-docks"), let list = obj["docks"] as? [[String: Any]] else { return [] }
        return list.map { (($0["id"] as? String) ?? "", ($0["name"] as? String) ?? "Dock",
                           ($0["online"] as? Bool) ?? false, ($0["current"] as? Bool) ?? false) }
    }
    /// Choose (or clear) the Dock this phone prints from.
    @discardableResult func setMirrorFrom(_ dockId: String?) async -> Bool {
        let obj = try? await post("mirror-from", ["dock_id": dockId as Any])
        return obj?["ok"] as? Bool == true
    }

    private func post(_ path: String, _ extra: [String: Any] = [:]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: "\(Prefs.cloudBase)/api/device/\(path)")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject:
            ["id": Identity.shared.deviceId, "secret": Identity.shared.secret].merging(extra) { _, b in b })
        let (data, _) = try await URLSession.shared.data(for: req)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func sendStatus() async {
        let sheets = SheetStore.shared.sheets.map { s in
            ["id": s.id, "name": s.name,
             "size": "\(s.model.width)×\(s.model.height) \(s.model.palette)", "palette": s.model.palette]
        }
        try? await send(["t": "status", "printer": Prefs.printerName, "state": "ready",
                         "version": GO_IOS_VERSION, "identifier": "touch",
                         "jobs_today": Prefs.printedToday, "sheets": sheets])
    }

    private func send(_ obj: [String: Any]) async throws {
        let data = try JSONSerialization.data(withJSONObject: obj)
        try await task?.send(.string(String(decoding: data, as: UTF8.self)))
    }
}
