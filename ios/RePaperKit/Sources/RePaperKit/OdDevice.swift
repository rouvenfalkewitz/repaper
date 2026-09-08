import Foundation

/// One BLE link to a sheet, transport-agnostic: CoreBluetooth implements OdLink in the app;
/// tests script it. Auth, interrogate, legacy uncompressed direct-write upload with per-chunk ACKs.
public protocol OdLink: Sendable {
    func write(_ data: Data, withResponse: Bool) async throws
    func read(timeoutMs: Int) async throws -> Data
}

public actor OdDevice {
    let link: OdLink
    let masterKey: Data?
    var sessionKey: Data?
    var sessionId: Data?
    var nonceCounter: UInt64 = 0
    public private(set) var capabilities: Capabilities?
    public private(set) var lastConfigHex: String?

    public static let timeoutAck = 5_000
    public static let timeoutFirstChunk = 10_000
    public static let timeoutConfigChunk = 2_000
    public static let timeoutDataAck = 90_000
    public static let timeoutRefresh = 90_000

    public init(link: OdLink, masterKey: Data? = nil) {
        self.link = link; self.masterKey = masterKey
    }

    public var isAuthenticated: Bool { sessionKey != nil }

    /// Test hook: preload capabilities as if interrogate had run.
    public func _setCapabilitiesForTesting(_ c: Capabilities) { capabilities = c }

    public func authenticate() async throws {
        guard let key = masterKey else { throw OdError.authError("sheet requires a key but none is registered") }
        var challenge: (Data, Data)?
        for attempt in 0..<2 {
            try await link.write(Od.authStep1(), withResponse: true)
            do { challenge = try Od.parseAuthChallenge(try await link.read(timeoutMs: Self.timeoutAck)); break }
            catch OdError.authError(let m) where m.contains("active session") && attempt == 0 { continue }
        }
        guard let (serverNonce, deviceId) = challenge else { throw OdError.authError("no auth challenge") }
        let clientNonce = OdCrypto.clientNonce()
        try await link.write(Od.authStep2(clientNonce: clientNonce,
            challenge: OdCrypto.challengeResponse(master: key, serverNonce: serverNonce, clientNonce: clientNonce, deviceId: deviceId)), withResponse: true)
        let proof = try Od.parseAuthSuccess(try await link.read(timeoutMs: Self.timeoutAck))
        let sk = OdCrypto.deriveSessionKey(master: key, clientNonce: clientNonce, serverNonce: serverNonce, deviceId: deviceId)
        guard proof == OdCrypto.serverProof(sessionKey: sk, serverNonce: serverNonce, clientNonce: clientNonce, deviceId: deviceId) else {
            throw OdError.authError("device failed mutual authentication")
        }
        sessionKey = sk
        sessionId = OdCrypto.deriveSessionId(sessionKey: sk, clientNonce: clientNonce, serverNonce: serverNonce)
        nonceCounter = 0
    }

    public func interrogate() async throws -> Capabilities {
        try await write(Od.readConfig())
        var resp = try await read(timeoutMs: Self.timeoutFirstChunk)
        if resp.count == 4, resp[resp.startIndex] == 0xFF, Int(resp[resp.startIndex + 1]) == (Od.readConfigCode & 0xFF) {
            throw OdError.protocolError("device has no stored configuration")
        }
        var chunk = Od.stripEcho(resp, expected: Od.readConfigCode)
        guard chunk.count >= 4 else { throw OdError.protocolError("config first chunk too short") }
        let total = Int(chunk[chunk.startIndex + 2]) | Int(chunk[chunk.startIndex + 3]) << 8
        var tlv = Data(chunk.dropFirst(4))
        while tlv.count < total {
            resp = try await read(timeoutMs: Self.timeoutConfigChunk)
            chunk = Od.stripEcho(resp, expected: Od.readConfigCode)
            guard chunk.count > 2 else { throw OdError.protocolError("config read stalled") }
            tlv.append(chunk.dropFirst(2))
        }
        lastConfigHex = tlv.hexLower
        let caps = try OdConfig.parse(tlv)
        capabilities = caps
        return caps
    }

    public func print(_ page: Page, fullRefresh: Bool = true, narrate: @Sendable (String) -> Void = { _ in }) async throws {
        if masterKey != nil && !isAuthenticated { narrate("connecting"); try await authenticate() }
        let caps: Capabilities
        if let c = capabilities { caps = c } else { narrate("reading the sheet"); caps = try await interrogate() }
        guard caps.viewedWidth == page.model.width && caps.viewedHeight == page.model.height else {
            throw OdError.protocolError("sheet shows \(caps.viewedWidth)×\(caps.viewedHeight), page is \(page.model.width)×\(page.model.height) — re-register the sheet")
        }
        let native = OdEncoding.rotateToNative(page.indexes, viewedW: page.model.width, viewedH: page.model.height, rotation: caps.rotation)
        let data = OdEncoding.encode(native, width: caps.width, height: caps.height, scheme: caps.scheme, panelIc: caps.panelIc)

        narrate("connected — sending")
        try await write(Od.directWriteStartUncompressed())
        try Od.validateAck(try await read(timeoutMs: Self.timeoutFirstChunk), expected: Od.directWriteStartCode)

        let chunkSize = isAuthenticated ? Od.encryptedChunkSize : Od.chunkSize
        var sent = 0
        var autoCompleted = false
        while sent < data.count {
            let chunk = Data(data.dropFirst(sent).prefix(chunkSize))
            try await write(Od.directWriteData(chunk), withResponse: false)
            sent += chunk.count
            let resp = try await read(timeoutMs: Self.timeoutDataAck)
            switch try Od.code(resp) & ~Od.ackFlag {
            case Od.directWriteEndCode: autoCompleted = true
            case Od.directWriteDataCode: break
            default: throw OdError.protocolError("unexpected response during upload")
            }
            if autoCompleted { break }
        }

        if !autoCompleted {
            try await write(Od.directWriteEnd(refreshMode: fullRefresh ? 0 : 1))
            try Od.validateAck(try await read(timeoutMs: Self.timeoutDataAck), expected: Od.directWriteEndCode)
        }
        narrate("sheet is refreshing (about 20 s)")
        switch try Od.code(try await read(timeoutMs: Self.timeoutRefresh)) & ~Od.ackFlag {
        case Od.refreshCompleteCode: narrate("printed")
        case Od.refreshTimeoutCode: throw OdError.protocolError("display refresh timed out")
        default: throw OdError.protocolError("unexpected response waiting for refresh")
        }
    }

    func write(_ data: Data, withResponse: Bool = true) async throws {
        if let sk = sessionKey, let sid = sessionId {
            let frame = OdCrypto.encryptCommand(sessionKey: sk, sessionId: sid, counter: nonceCounter,
                                                cmd: Data(data.prefix(2)), payload: Data(data.dropFirst(2)))
            nonceCounter += 1
            try await link.write(frame, withResponse: withResponse)
        } else {
            try await link.write(data, withResponse: withResponse)
        }
    }

    func read(timeoutMs: Int) async throws -> Data {
        let raw = try await link.read(timeoutMs: timeoutMs)
        if let sk = sessionKey, raw.count >= 31 {
            let (cmd, payload) = try OdCrypto.decryptResponse(sessionKey: sk, raw: raw)
            return Data([UInt8(cmd >> 8), UInt8(cmd & 0xFF)]) + payload
        }
        if raw.count == 3 && raw[raw.startIndex + 2] == 0xFE { throw OdError.authError("device requires an encryption key") }
        if raw.count == 3 && raw[raw.startIndex + 2] == 0xFF { throw OdError.protocolError("device rejected command: integrity check failed") }
        return raw
    }
}
