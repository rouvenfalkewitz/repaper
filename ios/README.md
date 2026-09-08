# RePaper Go for iOS

**Status (8 Sep 2026):** `RePaperKit/` — the complete protocol core (crypto with hand-rolled
CMAC + CCM over CommonCrypto, commands, config TLV, all nine panel encodings incl. per-panel
quirks, landing links, the async OdDevice upload actor) — is written as a line-by-line port
of the proven Kotlin core, against the **same golden fixtures** (`Tests/RePaperKitTests/golden`,
shared with `go/core`).

✅ **Verified (8 Sep 2026)**: with Xcode 26.3 installed, `swift test` passes — 8/8, including
the hand-rolled CMAC/CCM crypto matching py-opendisplay byte-for-byte and the full encrypted
upload flow against a fake sheet running firmware-side crypto.

## Next steps (needs Xcode from the App Store, ~12 GB)

1. `cd ios/RePaperKit && swift test`  → all golden tests must pass (fix what doesn't).
   Fallback runner without XCTest: see header of `Tests/golden_runner.swift`.
2. `brew install xcodegen && cd ios/App && xcodegen && open RePaperGo.xcodeproj`
3. Grow the shell (mirror the Android app): sign-in gate → auto-claim via /api/claim,
   ring main screen (LED language), sheets via QR (DataScanner) + NFC tap (CoreNFC),
   CoreBluetooth OdLink, **share extension** as the content intake (share-first — see
   docs/03-product-go.md), cloud agent (URLSessionWebSocketTask, kind "go").
4. Device: free Apple ID signing for the first runs (7-day), then Apple Developer
   Program → TestFlight; put the invite link on the cloud Updates page next to the APK.
