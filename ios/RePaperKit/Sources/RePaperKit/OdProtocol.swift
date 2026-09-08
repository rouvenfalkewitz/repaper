import Foundation

/// Command frames, response parsing and the config TLV — the legacy direct-write
/// subset every OpenDisplay firmware supports. Mirrors go/core OdProtocol.kt.
public enum Od {
    public static let serviceUUID = "00002446-0000-1000-8000-00805F9B34FB"
    public static let manufacturerId = 0x2446

    public static let readConfigCode = 0x0040
    public static let readFwVersionCode = 0x0043
    public static let authenticateCode = 0x0050
    public static let directWriteStartCode = 0x0070
    public static let directWriteDataCode = 0x0071
    public static let directWriteEndCode = 0x0072
    public static let refreshCompleteCode = 0x0073
    public static let refreshTimeoutCode = 0x0074
    public static let ackFlag = 0x8000

    public static let chunkSize = 230
    public static let encryptedChunkSize = 154

    static func cmd(_ code: Int) -> Data { Data([UInt8(code >> 8), UInt8(code & 0xFF)]) }

    public static func readConfig() -> Data { cmd(readConfigCode) }
    public static func readFwVersion() -> Data { cmd(readFwVersionCode) }
    public static func authStep1() -> Data { cmd(authenticateCode) + Data([0]) }
    public static func authStep2(clientNonce: Data, challenge: Data) -> Data {
        precondition(clientNonce.count == 16 && challenge.count == 16)
        return cmd(authenticateCode) + clientNonce + challenge
    }
    public static func directWriteStartUncompressed() -> Data { cmd(directWriteStartCode) }
    public static func directWriteData(_ chunk: Data) -> Data {
        precondition(chunk.count <= chunkSize)
        return cmd(directWriteDataCode) + chunk
    }
    public static func directWriteEnd(refreshMode: Int = 0) -> Data { cmd(directWriteEndCode) + Data([UInt8(refreshMode)]) }

    public static func code(_ data: Data, offset: Int = 0) throws -> Int {
        guard data.count >= offset + 2 else { throw OdError.protocolError("response too short for a command code") }
        return Int(data[data.startIndex + offset]) << 8 | Int(data[data.startIndex + offset + 1])
    }

    public static func validateAck(_ data: Data, expected: Int) throws {
        let c = try code(data)
        guard c == expected || c == (expected | ackFlag) else {
            throw OdError.protocolError(String(format: "ACK mismatch: expected 0x%04x, got 0x%04x", expected, c))
        }
    }

    public static func stripEcho(_ data: Data, expected: Int) -> Data {
        if data.count >= 2, let c = try? code(data), c == expected || c == (expected | ackFlag) {
            return Data(data.dropFirst(2))
        }
        return data
    }

    /// Step-1 auth response: [echo:2][status:1][server_nonce:16][device_id:4?]
    public static func parseAuthChallenge(_ data: Data) throws -> (Data, Data) {
        try checkAuthEcho(data)
        switch data[data.startIndex + 2] {
        case 0x00: break
        case 0x02: throw OdError.authError("device has an active session; retry")
        case 0x03: throw OdError.authError("device does not have encryption configured")
        case 0x04: throw OdError.authError("authentication rate limit exceeded — wait before retrying")
        default: throw OdError.authError("auth challenge failed")
        }
        guard data.count >= 19 else { throw OdError.protocolError("auth challenge too short") }
        let nonce = Data(data.dropFirst(3).prefix(16))
        let deviceId = data.count >= 23 ? Data(data.dropFirst(19).prefix(4)) : OdCrypto.defaultDeviceId
        return (nonce, deviceId)
    }

    /// Step-2 auth response: [echo:2][status:1][server_proof:16]
    public static func parseAuthSuccess(_ data: Data) throws -> Data {
        try checkAuthEcho(data)
        switch data[data.startIndex + 2] {
        case 0x00: break
        case 0x01: throw OdError.authError("wrong encryption key")
        case 0x04: throw OdError.authError("authentication rate limit exceeded — wait before retrying")
        default: throw OdError.authError("authentication failed")
        }
        guard data.count >= 19 else { throw OdError.protocolError("auth success too short") }
        return Data(data.dropFirst(3).prefix(16))
    }

    static func checkAuthEcho(_ data: Data) throws {
        guard data.count >= 3 else { throw OdError.protocolError("auth response too short") }
        let c = try code(data)
        guard c == authenticateCode || c == (authenticateCode | ackFlag) else {
            throw OdError.protocolError("auth echo mismatch")
        }
    }
}

/// Just enough of the config TLV to know what the panel is (display packet 0x20).
public enum OdConfig {
    static let sizes: [Int: Int] = [
        0x01: 22, 0x02: 22, 0x04: 30, 0x20: 46, 0x21: 22, 0x22: 30,
        0x24: 30, 0x25: 30, 0x26: 160, 0x27: 64, 0x28: 32, 0x29: 32,
        0x2A: 32, 0x2B: 32,
    ]

    /// Input: full TLV bytes (wrapper [len:2LE][version:1][packets][crc:2] included).
    public static func parse(_ raw: Data) throws -> Capabilities {
        guard raw.count >= 5 else { throw OdError.protocolError("config too short") }
        let packets = [UInt8](raw.dropFirst(3).dropLast(2))
        var off = 0
        while off + 2 <= packets.count {
            let type = Int(packets[off + 1]); off += 2
            guard let size = sizes[type] else { break }
            guard off + size <= packets.count else { break }
            let p = Array(packets[off..<(off + size)]); off += size
            if type == 0x20 {
                // <BBHHHHHHBBBBBBBBBB> LE: instance(0) tech(1) panel_ic(2-3) width(4-5) height(6-7)
                // mm_w(8-9) mm_h(10-11) tag_type(12-13) rotation(14) 5 pins(15-19)
                // partial(20) color_scheme(21) trans_modes(22) clk(23)
                let panelIc = Int(p[2]) | Int(p[3]) << 8
                let w = Int(p[4]) | Int(p[5]) << 8
                let h = Int(p[6]) | Int(p[7]) << 8
                let rot = (Int(p[14]) % 4) * 90
                let scheme = try ColorScheme.fromWire(Int(p[21]))
                return Capabilities(width: w, height: h, scheme: scheme, rotation: rot, panelIc: panelIc)
            }
        }
        throw OdError.protocolError("device config has no display packet")
    }
}

/// The QR/NFC landing link a sheet carries: 23 bytes = tag_type u16 BE, id 3 B, AES key 16 B, mfr u16 BE.
public struct Landing: Sendable {
    public let tagType: Int
    public let deviceId: String
    public let name: String
    public let keyHex: String?
    public let manufacturerId: Int

    public static func parse(_ url: String) throws -> Landing {
        let tok = url.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: "?").last!
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var b64 = tok.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let raw = Data(base64Encoded: b64), raw.count == 23 else {
            throw OdError.protocolError("not an OpenDisplay landing URL")
        }
        let key = raw.dropFirst(5).prefix(16)
        let id = Data(raw.dropFirst(2).prefix(3)).hexLower.uppercased()
        return Landing(
            tagType: Int(raw[0]) << 8 | Int(raw[1]),
            deviceId: id, name: "OD" + id,
            keyHex: key.allSatisfy { $0 == 0 } ? nil : key.hexLower,
            manufacturerId: Int(raw[21]) << 8 | Int(raw[22]))
    }
}
