# RePaper Go for iOS

**Status (8 Sep 2026):** `RePaperKit/` — the complete protocol core (crypto with hand-rolled
CMAC + CCM over CommonCrypto, commands, config TLV, all nine panel encodings incl. per-panel
quirks, landing links, the async OdDevice upload actor) — is written as a line-by-line port
of the proven Kotlin core, against the **same golden fixtures** (`Tests/RePaperKitTests/golden`,
shared with `go/core`).

⚠️ **Not yet compiled**: this Mac's Command Line Tools are broken (compiler/SDK build
mismatch on the macOS beta), so unlike everything else in this repo the Swift code has not
been machine-verified yet. First Xcode session must start with the golden tests.

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
