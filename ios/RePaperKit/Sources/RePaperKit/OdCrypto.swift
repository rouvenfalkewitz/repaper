import Foundation
import CommonCrypto

/// AES-128-CCM/CMAC session crypto, byte-for-byte the firmware's scheme.
/// CryptoKit has neither CMAC nor CCM, so both are implemented here over
/// CommonCrypto's AES-ECB — and pinned by the shared golden fixtures.
public enum OdCrypto {
    static let tagLen = 12
    public static let defaultDeviceId = Data([0x00, 0x00, 0x00, 0x01])

    // ── primitives ───────────────────────────────────────────────────────────

    static func aesEcbEncryptBlock(key: Data, block: Data) -> Data {
        precondition(key.count == 16 && block.count == 16)
        var out = Data(count: 16)
        var moved = 0
        _ = out.withUnsafeMutableBytes { ob in
            key.withUnsafeBytes { kb in
                block.withUnsafeBytes { bb in
                    CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionECBMode), kb.baseAddress, 16, nil,
                            bb.baseAddress, 16, ob.baseAddress, 16, &moved)
                }
            }
        }
        return out
    }

    /// AES-CMAC (RFC 4493).
    public static func cmac(key: Data, data: Data) -> Data {
        func dbl(_ b: Data) -> Data {
            var out = Data(count: 16); var carry: UInt8 = 0
            for i in stride(from: 15, through: 0, by: -1) {
                let v = b[b.startIndex + i]
                out[i] = (v << 1) | carry
                carry = (v & 0x80) != 0 ? 1 : 0
            }
            if carry == 1 { out[15] ^= 0x87 }
            return out
        }
        let l = aesEcbEncryptBlock(key: key, block: Data(count: 16))
        let k1 = dbl(l), k2 = dbl(k1)

        var blocks = data.count == 0 ? 1 : (data.count + 15) / 16
        let complete = data.count > 0 && data.count % 16 == 0
        var last = Data(count: 16)
        if complete {
            let start = data.index(data.startIndex, offsetBy: (blocks - 1) * 16)
            last = Data(data[start...])
            for i in 0..<16 { last[i] ^= k1[k1.startIndex + i] }
        } else {
            let start = data.index(data.startIndex, offsetBy: (blocks - 1) * 16)
            let rem = Data(data[start...])
            var padded = rem; padded.append(0x80)
            while padded.count < 16 { padded.append(0) }
            last = padded
            for i in 0..<16 { last[i] ^= k2[k2.startIndex + i] }
        }
        if data.isEmpty { blocks = 1 }

        var x = Data(count: 16)
        for i in 0..<(blocks - 1) {
            let start = data.index(data.startIndex, offsetBy: i * 16)
            let end = data.index(start, offsetBy: 16)
            var y = Data(data[start..<end])
            for j in 0..<16 { y[j] ^= x[x.startIndex + j] }
            x = aesEcbEncryptBlock(key: key, block: y)
        }
        var y = last
        for j in 0..<16 { y[j] ^= x[x.startIndex + j] }
        return aesEcbEncryptBlock(key: key, block: y)
    }

    /// AES-CCM (RFC 3610), 13-byte nonce, 12-byte tag — the firmware's exact parameters.
    static func ccm(key: Data, nonce: Data, aad: Data, plaintext: Data?, ciphertextAndTag: Data?) throws -> Data {
        precondition(nonce.count == 13)
        let L = 2   // 15 - nonce length
        func counterBlock(_ i: Int) -> Data {
            var b = Data([UInt8(L - 1)]); b.append(nonce)
            b.append(UInt8((i >> 8) & 0xFF)); b.append(UInt8(i & 0xFF))
            return b
        }
        func mac(_ msg: Data) -> Data {
            var b0 = Data([UInt8((aad.isEmpty ? 0 : 0x40) | (((tagLen - 2) / 2) << 3) | (L - 1))])
            b0.append(nonce)
            b0.append(UInt8((msg.count >> 8) & 0xFF)); b0.append(UInt8(msg.count & 0xFF))
            var blocks = b0
            if !aad.isEmpty {
                var a = Data([UInt8((aad.count >> 8) & 0xFF), UInt8(aad.count & 0xFF)])
                a.append(aad)
                while a.count % 16 != 0 { a.append(0) }
                blocks.append(a)
            }
            var m = msg
            while m.count % 16 != 0 { m.append(0) }
            blocks.append(m)
            var x = Data(count: 16)
            for i in stride(from: 0, to: blocks.count, by: 16) {
                var y = Data(blocks[blocks.index(blocks.startIndex, offsetBy: i)..<blocks.index(blocks.startIndex, offsetBy: i + 16)])
                for j in 0..<16 { y[j] ^= x[x.startIndex + j] }
                x = aesEcbEncryptBlock(key: key, block: y)
            }
            return x
        }
        func ctr(_ data: Data) -> Data {
            var out = Data(capacity: data.count)
            for i in stride(from: 0, to: data.count, by: 16) {
                let ks = aesEcbEncryptBlock(key: key, block: counterBlock(i / 16 + 1))
                let end = min(i + 16, data.count)
                for j in i..<end { out.append(data[data.startIndex + j] ^ ks[ks.startIndex + (j - i)]) }
            }
            return out
        }
        let a0 = aesEcbEncryptBlock(key: key, block: counterBlock(0))

        if let pt = plaintext {                                  // encrypt
            let t = mac(pt)
            var tag = Data(); for j in 0..<tagLen { tag.append(t[t.startIndex + j] ^ a0[a0.startIndex + j]) }
            return ctr(pt) + tag
        }
        let ct = ciphertextAndTag!                               // decrypt + verify
        guard ct.count >= tagLen else { throw OdError.protocolError("ciphertext too short") }
        let body = Data(ct[ct.startIndex..<ct.index(ct.endIndex, offsetBy: -tagLen)])
        let tag = Data(ct[ct.index(ct.endIndex, offsetBy: -tagLen)...])
        let pt = ctr(body)
        let t = mac(pt)
        var expect = Data(); for j in 0..<tagLen { expect.append(t[t.startIndex + j] ^ a0[a0.startIndex + j]) }
        guard expect == tag else { throw OdError.protocolError("integrity check failed") }
        return pt
    }

    // ── the firmware's session scheme ────────────────────────────────────────

    public static func deriveSessionKey(master: Data, clientNonce: Data, serverNonce: Data, deviceId: Data = defaultDeviceId) -> Data {
        var input = Data("OpenDisplay session".utf8)
        input.append(0); input.append(deviceId); input.append(clientNonce); input.append(serverNonce)
        input.append(contentsOf: [0x00, 0x80])
        let intermediate = cmac(key: master, data: input)
        var final = Data(count: 7); final.append(1)              // counter 1, big-endian 8 bytes
        final.append(intermediate.prefix(8))
        return aesEcbEncryptBlock(key: master, block: final)
    }

    public static func deriveSessionId(sessionKey: Data, clientNonce: Data, serverNonce: Data) -> Data {
        Data(cmac(key: sessionKey, data: clientNonce + serverNonce).prefix(8))
    }

    public static func challengeResponse(master: Data, serverNonce: Data, clientNonce: Data, deviceId: Data = defaultDeviceId) -> Data {
        cmac(key: master, data: serverNonce + clientNonce + deviceId)
    }

    public static func serverProof(sessionKey: Data, serverNonce: Data, clientNonce: Data, deviceId: Data = defaultDeviceId) -> Data {
        cmac(key: sessionKey, data: serverNonce + clientNonce + deviceId)
    }

    /// [cmd:2][session_id:8 ‖ counter:8 BE][ciphertext][tag:12]
    public static func encryptCommand(sessionKey: Data, sessionId: Data, counter: UInt64, cmd: Data, payload: Data) -> Data {
        var nonceFull = sessionId
        for i in stride(from: 56, through: 0, by: -8) { nonceFull.append(UInt8((counter >> UInt64(i)) & 0xFF)) }
        var plaintext = Data([UInt8(payload.count)]); plaintext.append(payload)
        let ct = try! ccm(key: sessionKey, nonce: Data(nonceFull.dropFirst(3)), aad: cmd, plaintext: plaintext, ciphertextAndTag: nil)
        return cmd + nonceFull + ct
    }

    /// Parses [cmd:2][nonce:16][ciphertext][tag:12] → (cmdCode, payload).
    public static func decryptResponse(sessionKey: Data, raw: Data) throws -> (Int, Data) {
        guard raw.count >= 2 + 16 + 1 + tagLen else { throw OdError.protocolError("encrypted response too short") }
        let cmd = Data(raw.prefix(2))
        let nonceFull = Data(raw.dropFirst(2).prefix(16))
        let ct = Data(raw.dropFirst(18))
        let pt = try ccm(key: sessionKey, nonce: Data(nonceFull.dropFirst(3)), aad: cmd, plaintext: nil, ciphertextAndTag: ct)
        let len = Int(pt[pt.startIndex])
        let payload = Data(pt.dropFirst().prefix(len))
        return (Int(cmd[cmd.startIndex]) << 8 | Int(cmd[cmd.startIndex + 1]), payload)
    }

    public static func clientNonce() -> Data {
        var d = Data(count: 16)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        return d
    }
}
