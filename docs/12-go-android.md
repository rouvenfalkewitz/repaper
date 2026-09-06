# RePaper Go (Android)

The full stack on a phone — no Dock needed. Lives in `go/` (Gradle, two modules):

- **`go/core`** — pure-JVM Kotlin port of the Dock's pipeline, mirroring its layering:
  landing-URL codec (QR key), OpenDisplay BLE protocol (auth/CCM sessions, config TLV,
  legacy direct-write upload), panel encodings (MONO/BWR/BWY/BWRY), and the render
  pipeline (trim → auto-rotate → contain → Floyd–Steinberg to the sheet palette).
  **Verified by golden tests**: `go/tools/make_golden.py` (run with the Dock's venv)
  generates byte-exact fixtures from py-opendisplay — the same SDK the Dock uses —
  and the Kotlin must match them bit for bit. 16 tests, including a fully simulated
  encrypted upload where the fake sheet runs the firmware-side crypto.
- **`go/app`** — the Android shell: `GoPrintService` (shows "RePaper Go" in every
  app's print dialog, spools the PDF, notifies), `GattLink` (BluetoothGatt as the
  core's `OdLink`), registry + cloud identity (same JSON shapes as `~/.repaper`),
  `CloudAgent` (WSS device channel, kind `"go"` — appears in the fleet next to Docks),
  and a minimal Paper-palette UI (add sheet by QR link, pick sheet per job, test page).

Build: `cd go && ./gradlew :core:test :app:assembleDebug`
(JDK 21 via `JAVA_HOME=/opt/homebrew/opt/openjdk@21`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`).

Untested until a phone exists: BLE against a real sheet, the print dialog flow,
runtime permissions. The protocol layer is fixture-proven; the GATT glue is the risk.
