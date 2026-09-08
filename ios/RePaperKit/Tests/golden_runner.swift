// Standalone golden-test runner: the same checks as GoldenTests.swift, runnable with
// bare swiftc (no SwiftPM, no XCTest) — for machines that only have Command Line Tools.
//   swiftc Sources/RePaperKit/*.swift Tests/golden_runner.swift -parse-as-library -o /tmp/rpk-test
//   /tmp/rpk-test Tests/RePaperKitTests/golden
import Foundation

var failures = 0
func check(_ ok: Bool, _ name: String) {
    if ok { print("  ✓ \(name)") } else { print("  ✗ \(name)"); failures += 1 }
}
func eq<T: Equatable>(_ a: T, _ b: T, _ name: String) { check(a == b, name + (a == b ? "" : "  [\(a) ≠ \(b)]")) }

@main struct Runner {
    static func main() async throws {
        let dir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Tests/RePaperKitTests/golden"
        func golden(_ name: String) throws -> [String: Any] {
            let data = try Data(contentsOf: URL(fileURLWithPath: "\(dir)/\(name).json"))
            return try JSONSerialization.jsonObject(with: data) as! [String: Any]
        }
        func hexData(_ v: Any?) -> Data { Data(hexString: v as! String)! }

        // ── crypto ───────────────────────────────────────────────────────────
        let c = try golden("crypto")
        let master = hexData(c["master"]), cn = hexData(c["client_nonce"])
        let sn = hexData(c["server_nonce"]), devId = hexData(c["device_id"])
        let sk = OdCrypto.deriveSessionKey(master: master, clientNonce: cn, serverNonce: sn, deviceId: devId)
        eq(c["session_key"] as! String, sk.hexLower, "session key")
        eq(c["session_id"] as! String, OdCrypto.deriveSessionId(sessionKey: sk, clientNonce: cn, serverNonce: sn).hexLower, "session id")
        eq(c["challenge_response"] as! String, OdCrypto.challengeResponse(master: master, serverNonce: sn, clientNonce: cn, deviceId: devId).hexLower, "challenge response")
        eq(c["server_proof"] as! String, OdCrypto.serverProof(sessionKey: sk, serverNonce: sn, clientNonce: cn, deviceId: devId).hexLower, "server proof")
        let sid = hexData(c["session_id"])
        let frame = OdCrypto.encryptCommand(sessionKey: sk, sessionId: sid, counter: UInt64(c["frame_counter"] as! Int),
                                            cmd: hexData(c["frame_cmd"]), payload: hexData(c["frame_payload"]))
        eq(c["frame"] as! String, frame.hexLower, "CCM frame")
        let (rcmd, rpayload) = try OdCrypto.decryptResponse(sessionKey: sk, raw: hexData(c["resp_frame"]))
        eq(c["resp_cmd"] as! Int, rcmd, "CCM decrypt cmd")
        eq(c["resp_payload"] as! String, rpayload.hexLower, "CCM decrypt payload")

        // ── commands ─────────────────────────────────────────────────────────
        let g = try golden("commands")
        eq(g["read_config"] as! String, Od.readConfig().hexLower, "read_config frame")
        eq(g["auth1"] as! String, Od.authStep1().hexLower, "auth1 frame")
        eq(g["auth2"] as! String, Od.authStep2(clientNonce: hexData(c["client_nonce"]), challenge: hexData(c["challenge_response"])).hexLower, "auth2 frame")
        eq(g["dw_start"] as! String, Od.directWriteStartUncompressed().hexLower, "dw start")
        eq(g["dw_data"] as! String, Od.directWriteData(Data([1, 2, 3, 4])).hexLower, "dw data")
        eq(g["dw_end_full"] as! String, Od.directWriteEnd(refreshMode: 0).hexLower, "dw end")

        // ── encodings ────────────────────────────────────────────────────────
        let e = try golden("encoding")
        let grid = e["grid"] as! [String]
        let h = grid.count, w = grid[0].count
        let repaper: [Character: Int] = ["W": 0, "B": 1, "R": 2, "Y": 3]
        func indexes(clamp: Bool, yAccent: Bool = false) -> [Int] {
            grid.flatMap { row in row.map { ch -> Int in
                var i = repaper[ch]!
                if yAccent && i == 3 { i = 2 }
                if clamp && i >= 2 { i = 1 }
                return i
            } }
        }
        eq(e["mono"] as! String, OdEncoding.encode(indexes(clamp: true), width: w, height: h, scheme: .mono).hexLower, "mono")
        eq(e["bwr"] as! String, OdEncoding.encode(indexes(clamp: false, yAccent: true), width: w, height: h, scheme: .bwr).hexLower, "bwr planes")
        eq(e["bwy"] as! String, OdEncoding.encode(indexes(clamp: false, yAccent: true), width: w, height: h, scheme: .bwy).hexLower, "bwy planes")
        eq(e["bwry"] as! String, OdEncoding.encode(indexes(clamp: false), width: w, height: h, scheme: .bwry).hexLower, "bwry")
        eq(e["bwry_swapped"] as! String, OdEncoding.encode(indexes(clamp: false), width: w, height: h, scheme: .bwry, panelIc: 0x001D).hexLower, "bwry swapped panel")

        let xg = e["xgrid"] as! [String]
        let xh = xg.count, xw = xg[0].count
        let six: [Character: Int] = ["B": 0, "W": 1, "Y": 2, "R": 3, "U": 4, "G": 5]
        func xidx(seven: Bool) -> [Int] { xg.flatMap { row in row.map { ch in ch == "O" ? (seven ? 6 : 3) : six[ch]! } } }
        eq(e["bwgbry"] as! String, OdEncoding.encode(xidx(seven: false), width: xw, height: xh, scheme: .bwgbry).hexLower, "bwgbry")
        eq(e["bwgbry_split"] as! String, OdEncoding.encode(xidx(seven: false), width: xw, height: xh, scheme: .bwgbrySplit).hexLower, "bwgbry split")
        eq(e["seven"] as! String, OdEncoding.encode(xidx(seven: true), width: xw, height: xh, scheme: .sevenColor).hexLower, "seven-color")
        let gg = e["graygrid"] as! [String]
        let gidx = gg.flatMap { row in row.map { Int(String($0))! } }
        eq(e["gray4_base"] as! String, OdEncoding.encode(gidx, width: 4, height: gg.count, scheme: .gray4, panelIc: 0x0008).hexLower, "gray4 base")
        eq(e["gray4_v2"] as! String, OdEncoding.encode(gidx, width: 4, height: gg.count, scheme: .gray4, panelIc: 0x0028).hexLower, "gray4 v2")
        let g16 = e["gray16_grid"] as! [[Int]]
        eq(e["gray16"] as! String, OdEncoding.encode(g16.flatMap { $0 }, width: 4, height: g16.count, scheme: .gray16).hexLower, "gray16")

        // ── landing ──────────────────────────────────────────────────────────
        let l = try golden("landing")
        let wk = l["with_key"] as! [String: Any]
        let la = try Landing.parse(wk["url"] as! String)
        eq(wk["name"] as! String, la.name, "landing name")
        eq(wk["key"] as! String, la.keyHex ?? "", "landing key")
        let nk = l["no_key"] as! [String: Any]
        check(try Landing.parse(nk["url"] as! String).keyHex == nil, "landing without key")

        // ── config ───────────────────────────────────────────────────────────
        let cf = try golden("config")
        let caps = try OdConfig.parse(hexData(cf["tlv"]))
        eq(cf["width"] as! Int, caps.width, "config width")
        eq(cf["height"] as! Int, caps.height, "config height")
        check(caps.scheme == .bwry, "config scheme (adversarial fixture)")
        eq(cf["rotation_degrees"] as! Int, caps.rotation, "config rotation")
        eq(cf["panel_ic"] as! Int, caps.panelIc, "config panel ic")

        // ── full encrypted upload against a fake sheet ───────────────────────
        let key = Data(hexString: "000102030405060708090a0b0c0d0e0f")!
        let sheet = FakeSheet(masterKey: key)
        let dev = OdDevice(link: sheet, masterKey: key)
        try await dev.authenticate()
        let authed = await dev.isAuthenticated
        check(authed, "encrypted session established (fake sheet ran firmware crypto)")
        await dev._setCapabilitiesForTesting(Capabilities(width: 250, height: 122, scheme: .bwr, rotation: 0))
        let model = SheetModel(width: 250, height: 122, palette: "BWR")
        let page = Page(indexes: (0..<(250 * 122)).map { $0 % 2 }, model: model)
        try await dev.print(page)
        eq(51, sheet.chunksReceived, "encrypted upload chunk count")

        // ── rotation ─────────────────────────────────────────────────────────
        eq([3, 0, 4, 1, 5, 2], OdEncoding.rotateToNative([0, 1, 2, 3, 4, 5], viewedW: 3, viewedH: 2, rotation: 90), "rotate 90")

        if failures > 0 { print("\n\(failures) failure(s)"); exit(1) }
        print("\nall RePaperKit golden tests green")
    }
}


final class FakeSheet: OdLink, @unchecked Sendable {
    let masterKey: Data?
    var toRead: [Data] = []
    var sessionKey: Data?
    let serverNonce = Data(repeating: 0x42, count: 16)
    var chunksReceived = 0

    init(masterKey: Data? = nil) { self.masterKey = masterKey }
    func ack(_ code: Int) -> Data { Data([UInt8(code >> 8), UInt8(code & 0xFF)]) }

    func write(_ data: Data, withResponse: Bool) async throws {
        var plain = data
        if let sk = sessionKey, data.count >= 31 {
            let (cmd, payload) = try OdCrypto.decryptResponse(sessionKey: sk, raw: data)
            plain = Data([UInt8(cmd >> 8), UInt8(cmd & 0xFF)]) + payload
        }
        switch try Od.code(plain) {
        case Od.authenticateCode: handleAuth(plain)
        case Od.directWriteStartCode: toRead.append(ack(Od.directWriteStartCode))
        case Od.directWriteDataCode: chunksReceived += 1; toRead.append(ack(Od.directWriteDataCode))
        case Od.directWriteEndCode:
            toRead.append(ack(Od.directWriteEndCode)); toRead.append(ack(Od.refreshCompleteCode))
        default: break
        }
    }

    func read(timeoutMs: Int) async throws -> Data {
        guard !toRead.isEmpty else { throw OdError.timeout("no response") }
        return toRead.removeFirst()
    }

    func handleAuth(_ plain: Data) {
        guard let key = masterKey else { return }
        if plain.count == 3 {
            toRead.append(ack(Od.authenticateCode) + Data([0]) + serverNonce + OdCrypto.defaultDeviceId)
        } else {
            let clientNonce = Data(plain.dropFirst(2).prefix(16))
            let sk = OdCrypto.deriveSessionKey(master: key, clientNonce: clientNonce, serverNonce: serverNonce)
            sessionKey = sk
            toRead.append(ack(Od.authenticateCode) + Data([0])
                + OdCrypto.serverProof(sessionKey: sk, serverNonce: serverNonce, clientNonce: clientNonce))
        }
    }
}
