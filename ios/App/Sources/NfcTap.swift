import Foundation
import CoreNFC

/// The result of one tap: the tag's permanent hardware serial (always readable, even
/// on a blank tag), plus its NDEF landing URI if it carries one.
struct TagRead {
    let uid: String
    let uri: String?
}

/// Tap-to-print, iOS edition. Uses a TAG-reader session (not just NDEF) so it can read
/// the hardware UID — that's what makes tapping work on sheets whose firmware can't
/// fill their own NDEF sticker: the app remembers the UID at setup and matches it here.
/// For sheets that DO carry a landing link, that still resolves the normal way.
final class NfcReader: NSObject, NFCTagReaderSessionDelegate {
    static var available: Bool { NFCTagReaderSession.readingAvailable }

    private var session: NFCTagReaderSession?
    private var onResult: ((TagRead?) -> Void)?

    /// One tap: returns the tag's UID (+ landing URI if present), or nil on cancel/failure.
    func scan(prompt: String, onResult: @escaping (TagRead?) -> Void) {
        self.onResult = onResult
        let s = NFCTagReaderSession(pollingOption: [.iso14443, .iso15693], delegate: self, queue: nil)
        s?.alertMessage = prompt
        session = s
        s?.begin()
    }

    private func finish(_ r: TagRead?) {
        DispatchQueue.main.async {
            self.onResult?(r)
            self.onResult = nil
            self.session = nil
        }
    }

    func tagReaderSessionDidBecomeActive(_ s: NFCTagReaderSession) {}
    func tagReaderSession(_ s: NFCTagReaderSession, didInvalidateWithError error: Error) { finish(nil) }

    func tagReaderSession(_ s: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else { s.invalidate(errorMessage: "No tag found."); return }
        let uidData = Self.uid(of: tag)
        let uid = uidData?.map { String(format: "%02X", $0) }.joined() ?? ""
        guard !uid.isEmpty else { s.invalidate(errorMessage: "That tag has no readable serial."); return }

        // the UID alone already makes tapping work; try to also read a landing link
        s.connect(to: tag) { [weak self] error in
            guard let self else { return }
            guard error == nil, let ndef = Self.ndefTag(tag) else {
                s.alertMessage = "Sheet recognised."; s.invalidate(); self.finish(TagRead(uid: uid, uri: nil)); return
            }
            ndef.readNDEF { message, _ in
                let uri = message.flatMap { Self.landingUri(in: $0) }
                s.alertMessage = "Sheet recognised."
                s.invalidate()
                self.finish(TagRead(uid: uid, uri: uri))
            }
        }
    }

    // ── tag helpers ──────────────────────────────────────────────────────────

    private static func uid(of tag: NFCTag) -> Data? {
        switch tag {
        case .miFare(let t): return t.identifier
        case .iso7816(let t): return t.identifier
        case .iso15693(let t): return t.identifier
        case .feliCa(let t): return t.currentIDm
        @unknown default: return nil
        }
    }

    private static func ndefTag(_ tag: NFCTag) -> NFCNDEFTag? {
        switch tag {
        case .miFare(let t): return t
        case .iso7816(let t): return t
        case .iso15693(let t): return t
        case .feliCa(let t): return t
        @unknown default: return nil
        }
    }

    private static func landingUri(in message: NFCNDEFMessage) -> String? {
        // proper URI records first; then any payload as text — field tags come back
        // with broken prefix codes, and the lenient Landing parser sorts out the rest
        message.records.compactMap { $0.wellKnownTypeURIPayload()?.absoluteString }.first
            ?? message.records.compactMap { r -> String? in
                guard let first = r.payload.first else { return nil }
                let body = (first < 0x20 || first > 0x7E) ? r.payload.dropFirst() : r.payload[...]
                return String(data: Data(body), encoding: .utf8)
            }.first
    }
}

// (The sheet programs its OWN NDEF tag over BLE — OdDevice.writeNfcUrl — when its
// firmware supports it. When it can't, the app remembers the tag's UID at setup
// instead, and matches it here. No phone-radio NDEF *writing* anywhere.)
