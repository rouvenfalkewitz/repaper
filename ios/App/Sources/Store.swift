import Foundation
import UIKit
import RePaperKit

let GO_IOS_VERSION = "0.1.29"

/// Which APNs environment this build's push tokens belong to. Development/Xcode
/// builds get sandbox tokens; flip to "production" for TestFlight/App Store.
let PUSH_ENV = "sandbox"

/// App-level preferences — the Android app's Prefs, UserDefaults edition.
enum Prefs {
    static let defaultCloud = "https://repaper.schisch.net"
    private static var d: UserDefaults { .standard }

    /// The cloud this app belongs to — reconfigurable on the sign-in page for on-prem installs.
    static var cloudBase: String {
        get {
            let v = (d.string(forKey: "cloud_base") ?? "").trimmingCharacters(in: .whitespaces)
            let t = v.hasSuffix("/") ? String(v.dropLast()) : v
            return t.isEmpty ? defaultCloud : t
        }
        set {
            let v = newValue.trimmingCharacters(in: .whitespaces)
            d.set(v.hasSuffix("/") ? String(v.dropLast()) : v, forKey: "cloud_base")
        }
    }

    /// Set once this device was claimed into an account; cleared when the fleet removes it.
    static var claimed: Bool {
        get { d.bool(forKey: "claimed_once") }
        set { d.set(newValue, forKey: "claimed_once") }
    }

    /// Members' devices wait for an admin; the app stays gated until this turns true.
    static var approved: Bool {
        get { d.object(forKey: "approved") == nil ? true : d.bool(forKey: "approved") }
        set { d.set(newValue, forKey: "approved") }
    }

    /// With several sheets: print on each in turn instead of asking.
    static var cycleSheets: Bool {
        get { d.bool(forKey: "sheet_cycle") }
        set { d.set(newValue, forKey: "sheet_cycle") }
    }

    /// Print2Go: the name of the Dock this phone prints from (nil = off). Display cache;
    /// the cloud holds the real relationship.
    static var print2goDock: String? {
        get { d.string(forKey: "print2go_dock") }
        set { newValue == nil ? d.removeObject(forKey: "print2go_dock") : d.set(newValue, forKey: "print2go_dock") }
    }
    /// One-time: has the phone been offered Print2Go on first launch?
    static var print2goOffered: Bool {
        get { d.bool(forKey: "print2go_offered") }
        set { d.set(newValue, forKey: "print2go_offered") }
    }
    static var cycleIx: Int { d.integer(forKey: "cycle_ix") }
    static func bumpCycleIx() { d.set(cycleIx + 1, forKey: "cycle_ix") }

    private static var todayKey: String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US")
        return "printed_" + f.string(from: Date())
    }
    static var printedToday: Int { d.integer(forKey: todayKey) }
    static func bumpPrinted() { d.set(printedToday + 1, forKey: todayKey) }

    /// The name people see in the fleet. Per-device default so two phones never collide.
    static var printerName: String {
        get { d.string(forKey: "printer_name") ?? "RePaper Go (\(UIDevice.current.model))" }
        set {
            let v = newValue.trimmingCharacters(in: .whitespaces)
            d.set(v.isEmpty ? "RePaper Go (\(UIDevice.current.model))" : v, forKey: "printer_name")
        }
    }
}

/// Cloud identity, generated once — mirrors the Dock's ~/.repaper/cloud.json.
struct Identity {
    static let shared = Identity()
    let deviceId: String
    let secret: String
    let claimCode: String

    private init() {
        let d = UserDefaults.standard
        func hex(_ n: Int) -> String {
            var b = [UInt8](repeating: 0, count: n)
            _ = SecRandomCopyBytes(kSecRandomDefault, n, &b)
            return b.map { String(format: "%02x", $0) }.joined()
        }
        if d.string(forKey: "cloud_device_id") == nil {
            d.set(hex(16), forKey: "cloud_device_id")
            d.set(hex(24), forKey: "cloud_secret")
            d.set("\(hex(2).uppercased())-\(hex(2).uppercased())", forKey: "cloud_claim_code")
        }
        deviceId = d.string(forKey: "cloud_device_id")!
        secret = d.string(forKey: "cloud_secret")!
        claimCode = d.string(forKey: "cloud_claim_code")!
    }
}

/// A registered sheet — same shape as the Dock's and Android's sheets.json entries.
struct Sheet: Identifiable, Equatable {
    let id: String
    var name: String
    var address: String
    var keyHex: String?
    var bleAddress: String?
    var landingUrl: String?   // the original QR/NFC link — needed to (re)program the tag
    var tagUid: String?       // the tag's hardware serial — fallback tap match when the tag has no landing link
    var model: SheetModel
    var dockName: String? = nil   // non-nil ⇒ inherited from that Dock (a Dock-Label)

    var isDockLabel: Bool { dockName != nil }
    static func == (a: Sheet, b: Sheet) -> Bool { a.id == b.id }
}

extension Sheet {
    /// Build an inherited (Dock-Label) sheet from a cloud `dock_sheets` entry. The AES key
    /// comes from parsing the landing link (same as a QR scan); the BLE address is
    /// discovered by name at print time, so it stays nil here.
    init?(fromCloud d: [String: Any], dockName: String?) {
        guard let id = d["id"] as? String, !id.isEmpty else { return nil }
        let link = (d["link"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let landing = link.flatMap { try? Landing.parse($0) }
        var model = SheetModel(width: 0, height: 0, palette: "BW")
        if let ms = d["model"] as? String,
           let md = try? JSONSerialization.jsonObject(with: Data(ms.utf8)) as? [String: Any],
           let w = md["width"] as? Int, let h = md["height"] as? Int {
            model = SheetModel(width: w, height: h, palette: md["palette"] as? String ?? "BW",
                               inset: md["inset"] as? [Int] ?? [0, 0, 0, 0])
        }
        self.init(id: id,
                  name: (d["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id,
                  address: (d["address"] as? String) ?? id,
                  keyHex: landing?.keyHex,
                  bleAddress: nil,
                  landingUrl: link,
                  tagUid: (d["tag_uid"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                  model: model,
                  dockName: dockName)
    }
}

/// sheets.json in Application Support: id → {name, transport, address, keys, model}.
/// The AES key from the QR link lives only here. This store holds the phone's OWN
/// (local) sheets, persisted; sheets inherited from a paired Dock (Dock-Labels) are kept
/// in memory and merged in for display and printing. `sheets` is the merged, de-duped view.
@MainActor final class SheetStore: ObservableObject {
    static let shared = SheetStore()
    @Published private(set) var sheets: [Sheet] = []   // merged: local + inherited, de-duped by landing name
    private var localSheets: [Sheet] = []              // phone-owned, persisted
    private var dockSheetsList: [Sheet] = []           // inherited from the paired Dock, in-memory
    private let file: URL

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        file = dir.appendingPathComponent("sheets.json")
        load()
    }

    /// Union by landing name (case-insensitive). A sheet present on the Dock wins (so it
    /// carries `dockName` and reads as a Dock-Label); a tag learned on either side survives.
    static func mergeSheets(local: [Sheet], dock: [Sheet]) -> [Sheet] {
        var byId: [String: Sheet] = [:]
        for s in local { byId[s.id.lowercased()] = s }
        for d in dock {
            var merged = d
            if merged.tagUid == nil { merged.tagUid = byId[d.id.lowercased()]?.tagUid }
            byId[d.id.lowercased()] = merged
        }
        return Array(byId.values).sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private func refresh() { sheets = Self.mergeSheets(local: localSheets, dock: dockSheetsList) }

    private func load() {
        guard let data = try? Data(contentsOf: file),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else { return }
        localSheets = obj.compactMap { id, e in
            guard let m = e["model"] as? [String: Any],
                  let w = m["width"] as? Int, let h = m["height"] as? Int else { return nil }
            let keys = e["keys"] as? [String: Any] ?? [:]
            return Sheet(id: id,
                         name: e["name"] as? String ?? id,
                         address: e["address"] as? String ?? id,
                         keyHex: (keys["key"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                         bleAddress: (keys["ble_address"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                         landingUrl: (keys["landing"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                         tagUid: (keys["tag_uid"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                         model: SheetModel(width: w, height: h,
                                           palette: m["palette"] as? String ?? "BW",
                                           inset: m["inset"] as? [Int] ?? [0, 0, 0, 0]))
        }.sorted { $0.id < $1.id }
        refresh()
    }

    private func save() {   // persists ONLY the phone's local sheets, never inherited ones
        var obj: [String: Any] = [:]
        for s in localSheets {
            var keys: [String: Any] = [:]
            if let k = s.keyHex { keys["key"] = k }
            if let b = s.bleAddress { keys["ble_address"] = b }
            if let l = s.landingUrl { keys["landing"] = l }
            if let u = s.tagUid { keys["tag_uid"] = u }
            obj[s.id] = ["name": s.name, "transport": "opendisplay-ble", "address": s.address, "keys": keys,
                         "model": ["width": s.model.width, "height": s.model.height,
                                   "palette": s.model.palette, "inset": s.model.inset]]
        }
        if let data = try? JSONSerialization.data(withJSONObject: obj, options: .prettyPrinted) {
            try? data.write(to: file)
        }
    }

    func add(_ sheet: Sheet) {
        localSheets.removeAll { $0.id == sheet.id }
        localSheets.append(sheet); localSheets.sort { $0.id < $1.id }
        save(); refresh()
    }
    func remove(_ id: String) { localSheets.removeAll { $0.id == id }; save(); refresh() }

    /// Replace the inherited set from the paired Dock (empty when unpaired).
    func setDockSheets(_ list: [Sheet]) { dockSheetsList = list; refresh() }

    func find(_ landing: Landing) -> Sheet? {
        sheets.first { $0.id.caseInsensitiveCompare(landing.name) == .orderedSame
                    || $0.address.caseInsensitiveCompare(landing.name) == .orderedSame }
    }
    func findByUid(_ uid: String) -> Sheet? {
        sheets.first { $0.tagUid?.caseInsensitiveCompare(uid) == .orderedSame }
    }
    /// Remember a tag's hardware serial — applies to the local and/or inherited copy and
    /// persists the local one. (Propagation to the Dock is the caller's job, over the socket.)
    func setTagUid(_ id: String, _ uid: String) {
        if let i = localSheets.firstIndex(where: { $0.id == id }) { localSheets[i].tagUid = uid; save() }
        if let j = dockSheetsList.firstIndex(where: { $0.id == id }) { dockSheetsList[j].tagUid = uid }
        refresh()
    }
}

/// The last 300 lines of what the app did — for the fleet's "Request diagnostics".
/// Never logs sheet keys.
final class DiagLog: @unchecked Sendable {
    static let shared = DiagLog()
    private var lines: [String] = []
    private let lock = NSLock()

    static func log(_ msg: String) {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
        shared.lock.lock(); defer { shared.lock.unlock() }
        shared.lines.append("\(f.string(from: Date())) \(msg)")
        if shared.lines.count > 300 { shared.lines.removeFirst(shared.lines.count - 300) }
    }

    static func dump() -> String {
        shared.lock.lock(); defer { shared.lock.unlock() }
        return shared.lines.joined(separator: "\n")
    }
}
