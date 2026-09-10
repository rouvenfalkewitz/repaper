# RePaper Go for iOS — the meticulous ledger

**Working mode (since 10 Sep 2026):** Go-app topics are built **iPhone-first and
meticulously**, every decision recorded here. Android is NOT touched per-feature —
everything it needs accumulates in the [Android parity backlog](#android-parity-backlog)
at the bottom and gets ported **in one final pass**.

Technical build/run details live in `ios/README.md`; this doc is product + decisions.

## Shipped and field-proven

| Area | State | Notes / decisions |
|---|---|---|
| Protocol core (RePaperKit) | ✅ 13/13 tests | Same golden fixtures as go/core; hand-rolled CMAC/CCM over CommonCrypto |
| Sign-in gate + auto-claim | ✅ on device | Consent dialog names the org; members see the approval warning; on-prem cloud URL in the footer |
| Approval-pending screen | ✅ | Signal-Blue setup ring; moves on by itself via the device channel |
| Ring main screen | ✅ | LED language per brand §06, ring geometry identical to the SVG mark |
| Cloud agent | ✅ | WebSocket kind "go", 5-min status heartbeat, remote diagnostics ("Request diagnostics" in the console) |
| CoreBluetooth link | ✅ **first print worked on first attempt** (10 Sep, iPhone 16 Pro) | Always writes with-response; stop-and-wait |
| Share-to-print | ✅ | Share extension spools PDFs/images into the app-group jobs folder; branded carbon panel |
| Job pipeline | ✅ | First-page thumbnails, tap-to-choose sheet, hold-to-discard, one-sheet auto-print, cycle-through-sheets |
| QR scanning | ✅ | VisionKit DataScanner, QR-only, primary path for adding sheets; paste stays as fallback |
| Password-manager autofill | ✅ | AASA (`webcredentials`, team 8DPLCLHB27) served by the cloud + associated domain (`?mode=developer` in dev builds) + proper textContentTypes incl. `.oneTimeCode` for 2FA |
| Sign out | ✅ | Device removes itself server-side (`POST /api/device/unclaim`, id+secret auth) — sheets stay, sign-in brings it back |
| Signing | ✅ | Team 8DPLCLHB27, automatic; `-allowProvisioningUpdates -allowProvisioningDeviceRegistration` does portal work |

## In progress / next (iOS only)

1. **App icon** — homescreen currently shows the blank placeholder. The ring mark on
   carbon, generated from the exact logo geometry.
2. **NFC tap-to-print** — iOS cannot read tags passively in the foreground like
   Android's reader mode; the UX is an explicit "Tap the sheet" button starting an
   `NFCNDEFReaderSession` (sheet held to the top edge). Same tap semantics as Android:
   known sheet + job → print; known, no job → say so; unknown → offer add (+print).
3. **TestFlight** — needs the App Store Connect app record (Rouven's ~5 clicks, or an
   ASC API key once for full automation). Then: archive upload, invite link on the
   cloud Updates page next to the APK, iOS changelog surfaced like the Android one.
4. **Distribution entitlements sanity pass** before TestFlight: drop `?mode=developer`
   from the associated domain for release builds; app group + NFC on the App IDs.
5. Nice-to-haves noticed while testing (unordered): move the device secret from
   UserDefaults to the Keychain; job badge/notification when a share arrives while
   the app is closed; haptics on print done/failed.

## Android parity backlog

Accumulates everything iOS got that Android doesn't have yet. Port in ONE pass at the end.

- **Password-manager autofill**: serve `/.well-known/assetlinks.json` (Digital Asset
  Links with the APK signing cert SHA-256) + `android:autofillHints` on the sign-in
  fields — the Android twin of the AASA work (10 Sep).
- **Share-first intake?** iOS is share-first by design; Android currently exposes a
  system print service instead. Open product decision (docs/03): add share intake to
  Android too, keep "expose printer" as an option. Decide before the parity pass.
- **App icon**: whatever icon ships on iOS should be mirrored to the Android launcher
  icon set (current Android icon predates it).
- *(Sign out, QR scanning, cycle-through-sheets, one-sheet auto-print: already on both.)*
