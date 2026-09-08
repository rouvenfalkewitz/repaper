# RePaper Go for iOS

**Status (8 Sep 2026):** two layers, both building green under Xcode 26.3.

- `RePaperKit/` — the complete protocol core (crypto with hand-rolled CMAC + CCM over
  CommonCrypto, commands, config TLV, all nine panel encodings incl. per-panel quirks,
  landing links, the async OdDevice upload actor), a line-by-line port of the proven
  Kotlin core against the **same golden fixtures** (`Tests/RePaperKitTests/golden`,
  shared with `go/core`). ✅ `swift test` → 8/8, byte-for-byte with py-opendisplay.
- `App/` — the shell, mirroring the Android app: sign-in gate with auto-claim via
  /api/claim (consent dialog, 2FA, on-prem cloud URL in the footer), approval-pending
  screen, ring main screen speaking the LED language, Settings (printer name, sheet
  cycling, sheet library with paste-a-link adding, cloud state), CloudAgent
  (URLSessionWebSocketTask, kind "go", diag support), CoreBluetooth OdLink + SheetOps.
  Bundled Archivo/Figtree variable fonts, Paper tokens throughout.
  ✅ All three routes (auth / pending / main) verified on the iOS 26.3 simulator.

## What still needs a real iPhone

- BLE (simulator has no CoreBluetooth): add-sheet + printing paths are written but
  field-untested. First device session: register a sheet by pasted link, then print.
- **Share extension** as the content intake (share-first — see docs/03-product-go.md),
  then QR scanning (DataScanner) and NFC tap-to-print (CoreNFC).

## Build & run

```
cd ios/RePaperKit && swift test            # golden parity
cd ios/App && xcodegen                     # regenerate RePaperGo.xcodeproj
xcodebuild -project RePaperGo.xcodeproj -scheme RePaperGo \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build build CODE_SIGNING_ALLOWED=NO
```

## Distribution

Free Apple ID signing for the first device runs (7-day), then Apple Developer Program
(enrollment in progress) → TestFlight; put the invite link on the cloud Updates page
next to the APK.
