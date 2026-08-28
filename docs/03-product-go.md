# 03 · Product 1 — RePaper Go (the app)

## Summary

An app that runs on a phone, tablet or laptop and makes that device appear on the local network as a printer. When a print job arrives, the app notifies the user; the user picks / holds the device next to a RePaper Sheet, and the job is "printed" onto that sheet.

Go is the low-cost entry point: no hardware to buy except sheets. It is also our fastest way to demo the concept and to validate the printer-emulation core.

## Reality check: web app vs. native app

"Pretending to be a printer" requires the device to **listen on a TCP port (IPP, 631 or similar) and announce itself via mDNS/Bonjour**. Browsers cannot do either. Therefore:

- **Mobile:** must be a native app (Android first — it allows a long-running foreground service with a TCP server and NSD/mDNS registration; iOS can do the same with the Network framework + Bonjour, but only reliably while the app is in the foreground, and NFC on iOS needs a native app anyway).
- **Desktop:** a small native/desktop app (e.g. Tauri, Electron, or a plain Go/Rust binary with a local web UI) — this can be the *same* web UI served locally, so "web app" is still true for the UI layer.
- A pure web app *can* still exist as the setup/monitoring UI of the Dock and the Cloud.

Recommendation: build the core once (see `05-architecture.md`), ship it as an Android app + a desktop app, with a shared web-tech UI on top.

## User flow

1. **Setup**
   - Install app, give the printer a name ("Rouven's RePaper"), done. The app registers itself on the Wi-Fi network as an AirPrint / IPP Everywhere printer.
   - Optional: sign in for cloud sync (sheet registry, job history, team sharing).
2. **Print** (from any other device on the network, or from the same device)
   - Print dialog shows "Rouven's RePaper" with paper sizes matching sheet sizes (e.g. *RePaper Card 4.2"*).
3. **Job arrives**
   - Push/local notification: "Print job *Pick list 4711* (1 page) waiting. Hold a sheet to print."
   - App shows a preview of the rendered page.
4. **Choose sheet**
   - **Tap:** hold the phone to the sheet's NFC tag → the app reads the sheet ID / model and transfers the image (via NFC directly, or via a radio transport — depends on the sheet hardware, see `06-epaper-hardware.md`).
   - **Or pick from list:** choose a known sheet by name ("Cart 12") if it is in radio range or reachable via a Dock.
5. **Done**
   - Sheet refreshes; app shows success; the job is reported as *completed* to the printing system. On failure, the job stays pending and the user can retry.

## Features (v1)

- Printer emulation: AirPrint (iOS/macOS), IPP Everywhere (Windows 10+/Linux), Mopria (Android).
- Render PDF / Apple Raster / PWG Raster to the target sheet (scale-to-fit, rotate, dither).
- Job inbox with preview, retry, discard; multi-page jobs → page-by-page onto multiple sheets ("batch tapping").
- Sheet registry: name your sheets, see last content, last update, battery (if the sheet reports it).
- Notifications: local, plus push via cloud when the app isn't in the foreground (Android foreground service keeps the printer listening).

## Features (later)

- Team mode: several phones share one printer name; whoever taps first prints.
- Templates: "always fit to 4.2"", "auto-rotate landscape", label-specific crop rules.
- Print-to-Dock relay: Go forwards jobs to a Dock in another room.
- Hand-written annotations (if sheets get touch later).

## Platform notes

| Platform | Printer listener | mDNS | NFC | Notes |
|---|---|---|---|---|
| Android | Foreground service, TCP 631/8631 | NsdManager | Full reader/writer, ISO14443 & ISO15693 | First target |
| iOS | Network framework listener | NetService/NWListener bonjour | Core NFC (NDEF, ISO7816, ISO15693 transceive) | Only reliable in foreground; App Store review may question a "printer" app — should be fine, it's just a local server |
| macOS / Windows / Linux | Local daemon | Bonjour / Avahi / built-in | External USB NFC reader (optional) | Desktop is mostly for demos and for "print from laptop to sheet on my desk" |

## Non-goals for v1

- No cloud printing from outside the LAN (ask a Dock for that).
- No colour management beyond dithering.
- No user accounts required — works fully offline.
