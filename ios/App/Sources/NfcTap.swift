import Foundation
import CoreNFC

/// Tap-to-print, iOS edition. Unlike Android's always-on reader mode, iOS only reads
/// tags inside an explicit session — so the main screen offers a "Tap the sheet"
/// button and this starts the system NFC sheet. The labels' built-in tag carries the
/// same landing link as the QR code.
final class NfcReader: NSObject, NFCNDEFReaderSessionDelegate {
    static var available: Bool { NFCNDEFReaderSession.readingAvailable }

    private var session: NFCNDEFReaderSession?
    private var onResult: ((String?) -> Void)?

    /// One scan: returns the first NDEF URI (or nil on cancel/timeout/no-URI).
    func scan(prompt: String, onResult: @escaping (String?) -> Void) {
        self.onResult = onResult
        let s = NFCNDEFReaderSession(delegate: self, queue: nil, invalidateAfterFirstRead: true)
        s.alertMessage = prompt
        session = s
        s.begin()
    }

    private func finish(_ uri: String?) {
        DispatchQueue.main.async {
            self.onResult?(uri)
            self.onResult = nil
            self.session = nil
        }
    }

    func readerSession(_ s: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {
        let records = messages.flatMap(\.records)
        // proper URI records first; then any payload as text — field tags come back
        // with broken prefix codes, and the lenient Landing parser sorts out the rest
        let uri = records.compactMap { $0.wellKnownTypeURIPayload()?.absoluteString }.first
            ?? records.compactMap { r -> String? in
                guard let first = r.payload.first else { return nil }
                let body = (first < 0x20 || first > 0x7E) ? r.payload.dropFirst() : r.payload[...]
                return String(data: Data(body), encoding: .utf8)
            }.first
        finish(uri)
    }

    func readerSession(_ s: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        finish(nil)   // no-op if didDetectNDEFs already delivered
    }

    func readerSessionDidBecomeActive(_ s: NFCNDEFReaderSession) {}
}

// (The sheet programs its OWN tag over BLE — OdDevice.writeNfcUrl — so there is
// deliberately no phone-radio NFC writer here.)
