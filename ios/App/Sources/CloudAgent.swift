import Foundation

extension Notification.Name {
    /// A Dock Light job just landed in the spool — the main screen refreshes.
    static let mirrorJobArrived = Notification.Name("mirrorJobArrived")
}

/// One outbound WebSocket to RePaper Cloud — the Dock's cloud.py in miniature, kind "go".
/// Printing never depends on it; the cloud sees metadata, never pages —
/// EXCEPT mirror jobs from a Dock Light, which arrive through the relay by design.
@MainActor final class CloudAgent: ObservableObject {
    static let shared = CloudAgent()

    @Published var state = "off"
    @Published var claimed = Prefs.claimed
    @Published var approved = Prefs.approved
    @Published var org: String?
    var claimCode: String { Identity.shared.claimCode }

    private var running = false
    private var task: URLSessionWebSocketTask?

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
                                "claim": Identity.shared.claimCode, "kind": "go", "version": GO_IOS_VERSION])
                state = "online"; backoff = 2
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
        case "mirror_job":
            // a Dock Light somewhere printed a page for THIS device — fetch and spool it
            if let job = msg["job"] as? [String: Any], let id = job["id"] as? String {
                await fetchMirrorJob(id: id, name: job["name"] as? String ?? "job")
            }
        case "diag":
            try? await send(["t": "diag", "log": DiagLog.dump()])
        default:
            break
        }
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

    /// The relay hands over the page and forgets it; the job joins the normal queue
    /// (one sheet auto-prints, several ask — exactly like a shared page).
    private func fetchMirrorJob(id: String, name: String) async {
        do {
            var req = URLRequest(url: URL(string: "\(Prefs.cloudBase)/api/device/mirror-job/\(id)")!)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: ["id": Identity.shared.deviceId, "secret": Identity.shared.secret])
            let (data, _) = try await URLSession.shared.data(for: req)
            guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  obj["ok"] as? Bool == true,
                  let b64 = obj["data"] as? String, let bytes = Data(base64Encoded: b64) else {
                DiagLog.log("mirror job \(id): fetch refused"); return
            }
            let ext = (obj["type"] as? String) == "pdf" ? "pdf" : "png"
            try bytes.write(to: JobStore.newJobURL(label: name, ext: ext))
            DiagLog.log("mirror job spooled: \(name) (\(bytes.count) B)")
            NotificationCenter.default.post(name: .mirrorJobArrived, object: nil)
        } catch {
            DiagLog.log("mirror job \(id) failed: \(error.localizedDescription)")
        }
    }

    private func send(_ obj: [String: Any]) async throws {
        let data = try JSONSerialization.data(withJSONObject: obj)
        try await task?.send(.string(String(decoding: data, as: UTF8.self)))
    }
}
