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

| App icon | ✅ CI-checked (10 Sep) | First version (bare ring + glow) was NOT CI — the official mark is ring **+ RE**, no glow. Now uses `brand/logo/export/app-icon/ios/AppIcon.appiconset` verbatim (incl. iOS 18 dark/tinted) |
| NFC tap-to-print | ✅ built (device-test pending) | iOS has no passive reader mode → explicit "Tap the sheet" button starts the NFC session; tap semantics identical to Android (known+job → print, unknown → add+print) |
| Brand lockup in-app | ✅ | Real RE\|PAPER lockup asset + "GO" in Archivo, Paper color (logo README: green never on letters), cap height matched — login + main header |
| Main-screen layout | ✅ | Hero at a FIXED offset per device; pill/title/subtitle/action slots have reserved heights so the ring never moves between states; "How to print" 3-step card in ready state |
| Autofill bug post-mortem | ✅ fixed (10 Sep) | `?mode=developer` alone is IGNORED unless Settings→Developer→"Associated Domains Development" is on → association silently off. Fix: list the plain domain AND the developer twin; Apple's CDN already serves our AASA (verified via app-site-association.cdn-apple.com) |
| Product lockup RE\|PAPER GO | ✅ (10 Sep) | Runtime-font "GO" didn't match → composed a single image through the logo pipeline's letterforms (Archivo wght 800 / wdth 125, −0.03 em, cap height from the export manifest). Gotcha: PIL variation axes are in fvar order (Weight, Width) |
| Tap semantics (Rouven, 10 Sep) | ✅ | **Adding sheets lives in Settings** (QR / NFC tap / paste). The main page's tap button appears ONLY when a job waits AND a choice is needed (>1 sheet, cycling off) |
| How-to-print card | ✅ redesigned | Visual pipeline (share icon → ring → mini e-paper sheet with ink lines) instead of a text list, one caption line |
| Settings redesign | 🔎 awaiting Rouven's review | Merged device card (icon rows + dividers), Dock-anatomy sheet cards (mono address + palette dots + real-aspect panel), add-card with Scan/Tap side by side + tucked-away paste link, cloud card with inline sign-out row |
| Visual-test hook | ✅ | `simctl launch … --open-settings` jumps straight to Settings for screenshot verification |

## In progress / next (iOS only)

1. **UX pass over everything** with Rouven (his ask, 10 Sep) — walk every screen/state.
2. **TestFlight** — needs the App Store Connect app record (Rouven's ~5 clicks, or an
   ASC API key once for full automation). Then: archive upload, invite link on the
   cloud Updates page next to the APK, iOS changelog surfaced like the Android one.
3. Nice-to-haves noticed while testing (unordered): move the device secret from
   UserDefaults to the Keychain; job badge/notification when a share arrives while
   the app is closed; haptics on print done/failed.

## NFC done right (10 Sep, all variants — Go iOS 0.1.2 · Go Android 0.2.9 · Dock 0.0.25)

Correction from Rouven: tags are NOT programmed with the phone's NFC radio. The
OpenDisplay protocol has an NFC endpoint (0x0083) — **the sheet writes its own tag
over BLE during registration**, in the same connection that interrogates it:
- Wire (golden-fixture-pinned in both cores, SDK `write_nfc_url` on the Dock):
  inline `[0083][01][rec][len:2BE][payload]` ≤120 B, chunked `10/11/12` up to 512 B,
  OK `[0083][81|82]`, error `[FF83FF][err]`, URI record type 1, commit ≈ slow I2C
  (15 s timeout). Older firmware stays silent on the first frame → "not supported",
  non-fatal.
- The value written is the landing URL (exactly what `Landing.parse` accepts), so
  tap-to-print always resolves. The link is stored per sheet; "Re-program the NFC
  tag" lives in the sheet options on both apps.
- Tap-to-print affordance: iOS got an accent capsule with animated radiating NFC
  waves + "or choose from the list" fallback (no-NFC devices get "Choose the
  sheet" as primary); Android got the same capsule as a passive beacon (its reader
  mode is always listening) with an ic_nfc glyph.

## Field diagnosis 10 Sep evening: sheet firmware 1.0.0 has NO NFC endpoint

Verified from the pilot Dock (SSH as repaper@, SDK driven directly against the
sheets in radio range): command 0x0083 gets pure silence — the vendor SDK's own
`write_nfc_url` raises NfcNotSupportedError, and the read sub-opcode is equally
dead. Both field observations explained: the empty tag stayed empty because the
write was (correctly) skipped, and the "unparseable" tag was never touched by us
— its content predates RePaper and is not a landing token (the 0.1.3 build shows
the raw content on tap). Our BLE write is correct and golden-pinned; it starts
working the moment sheets run firmware with the endpoint. The SDK ships OTA
machinery (nRF DFU / Silabs), so sheet-firmware updates are client-side possible
once vendor images exist. Side find: the Pi's BlueZ adapter was wedged
(org.bluez.Error.NotSupported on every connect) until a `bluetoothctl power
off/on` — worth watching, may explain past flaky dock prints.

Open decision (Rouven): for current-firmware sheets, tap-to-print needs either
(a) waiting for sheet firmware, (b) tag-FINGERPRINT linking — one tap stores
whatever the tag already holds and later taps resolve by match, no writing ever,
works for the junk-content sheet — or (c) an optional phone-radio write for
empty tags (the flow removed earlier by request).

## The 10 Sep adaptation pass (Android 0.2.8 · Dock 0.0.25)

Rouven's review findings, ported the same day ("adapt our findings to android and
the regular dock"):
- **Android**: official adaptive launcher icon (brand export, incl. monochrome),
  RE|PAPER GO lockup in main/auth headers, main chips removed, visual how-to card
  (share → mark → mini sheet), tap on unknown tag points to Settings (adding lives
  there), NFC tag programmed on add + "Program NFC tag" in sheet options (landing
  link stored per sheet), Settings as one card per section with dividers + gear
  behind the title + icon sign-out row + tech rows (Intake / Sheet link), section
  "Cloud".
- **Dock**: settings gains the same Printer/Sheets tech rows (Intake: AirPrint ·
  IPP; Sheet link: OpenDisplay BLE) — 0.0.25 tarball built, upload + rollout via
  the Updates page pending. NFC tag programming on the Dock waits for the RC522
  (no writer hardware yet) — requirement noted.

## Android parity backlog (remaining)

- **Password-manager autofill**: serve `/.well-known/assetlinks.json` (Digital Asset
  Links with the APK signing cert SHA-256) + `android:autofillHints` on the sign-in
  fields — the Android twin of the AASA work (10 Sep).
- **Share-first intake?** iOS is share-first by design; Android currently exposes a
  system print service instead. Open product decision (docs/03): add share intake to
  Android too, keep "expose printer" as an option.
- **Main-screen fixed-position hero** with reserved slots (iOS has it; Android's
  hero still shifts slightly between states).
- *(Sign out, QR scanning, cycle-through-sheets, one-sheet auto-print: already on both.)*
