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
        let uri = messages.flatMap(\.records)
            .compactMap { $0.wellKnownTypeURIPayload()?.absoluteString }
            .first
        finish(uri)
    }

    func readerSession(_ s: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        finish(nil)   // no-op if didDetectNDEFs already delivered
    }

    func readerSessionDidBecomeActive(_ s: NFCNDEFReaderSession) {}
}

/// Programs a sheet's tag with its landing link — some sheets ship with an empty
/// tag, so every RePaper variant writes the correct value when the sheet is added
/// (docs/13, finding of 10 Sep). Tap-to-print then always works.
final class NfcWriter: NSObject, NFCNDEFReaderSessionDelegate {
    private var session: NFCNDEFReaderSession?
    private var link: String = ""
    private var onDone: ((Bool) -> Void)?

    /// One write: returns true when the tag was programmed (false on cancel/failure).
    func write(_ link: String, prompt: String, onDone: @escaping (Bool) -> Void) {
        self.link = link
        self.onDone = onDone
        let s = NFCNDEFReaderSession(delegate: self, queue: nil, invalidateAfterFirstRead: false)
        s.alertMessage = prompt
        session = s
        s.begin()
    }

    private func finish(_ ok: Bool) {
        DispatchQueue.main.async {
            self.onDone?(ok)
            self.onDone = nil
            self.session = nil
        }
    }

    func readerSession(_ s: NFCNDEFReaderSession, didDetect tags: [NFCNDEFTag]) {
        guard let tag = tags.first, let url = URL(string: link),
              let record = NFCNDEFPayload.wellKnownTypeURIPayload(url: url) else {
            s.invalidate(errorMessage: "Couldn't prepare the tag content."); return
        }
        let message = NFCNDEFMessage(records: [record])
        s.connect(to: tag) { error in
            if error != nil { s.invalidate(errorMessage: "Couldn't reach the tag — try again."); return }
            tag.queryNDEFStatus { status, _, error in
                guard error == nil, status == .readWrite else {
                    s.invalidate(errorMessage: "This tag can't be written."); return
                }
                tag.writeNDEF(message) { error in
                    if let error {
                        s.invalidate(errorMessage: "Writing failed: \(error.localizedDescription)")
                    } else {
                        s.alertMessage = "Tag programmed — tapping this sheet works now."
                        s.invalidate()
                        self.finish(true)
                    }
                }
            }
        }
    }

    func readerSession(_ s: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {}
    func readerSession(_ s: NFCNDEFReaderSession, didInvalidateWithError error: Error) { finish(false) }
    func readerSessionDidBecomeActive(_ s: NFCNDEFReaderSession) {}
}
