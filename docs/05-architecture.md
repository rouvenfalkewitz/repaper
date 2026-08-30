# 05 · Architecture — the shared core

Both products (Go and Dock) are the same pipeline with different bodies:

```
 any client ──(mDNS discovery + IPP over HTTP)──▶ ┌─────────────────────────────┐
                                                  │ 1. Printer emulation (IPP)  │
                                                  ├─────────────────────────────┤
                                                  │ 2. Job queue + state        │
                                                  ├─────────────────────────────┤
                                                  │ 3. Renderer (PDF/URF/PWG →  │
                                                  │    sheet bitmap, dithered)  │
                                                  ├─────────────────────────────┤
                                                  │ 4. Sheet identification     │  ◀── NFC tap / list pick
                                                  ├─────────────────────────────┤
                                                  │ 5. Display transport(s)     │  ──▶ NFC direct / ESL radio / BLE / SoluM AP
                                                  └─────────────────────────────┘
                                                  │ UI adapter: app screens (Go) or LED + web UI (Dock)
```

Stack decision: see `08-ecosystem.md`. Short version — the Dock is built as a **PAPPL printer application** (C framework that already implements IPP Everywhere / AirPrint / Mopria, DNS-SD, raster decoding, job states and a web UI); our code is the sheet driver, the transport and the NFC/LED sidecar. The Android app gets its own small IPP listener later (Rust core via UniFFI, or Kotlin-native — decided when we start it).

## 1. Printer emulation

What "being a printer" means on the wire:

- **Discovery:** mDNS/DNS-SD service `_ipp._tcp` (and `_ipps._tcp` for TLS). Also `_universal._sub._ipp._tcp` (AirPrint) and `_print._sub._ipp._tcp` (IPP Everywhere / Mopria). TXT record carries the printer capabilities.
- **Protocol:** IPP (RFC 8010/8011) over HTTP on port 631, path `/ipp/print`. Operations to implement: `Get-Printer-Attributes`, `Validate-Job`, `Print-Job`, `Create-Job` + `Send-Document`, `Get-Jobs`, `Get-Job-Attributes`, `Cancel-Job`, `Identify-Printer` (we blink the LED!), `Close-Job`.
- **Document formats we accept:** `application/pdf`, `image/urf` (Apple Raster — *mandatory* for iOS, which sends URF), `image/pwg-raster` (IPP Everywhere / Mopria / Windows), `image/jpeg`, `image/png`. Optional later: PCL/PostScript for legacy industrial systems — parking this, most modern label tools can output PDF or use a Windows driver anyway.
- **AirPrint TXT essentials:** `txtvers=1`, `rp=ipp/print`, `pdl=application/pdf,image/urf,image/pwg-raster,image/jpeg`, `URF=W8,SRGB24,CP1,RS300` (adapt), `ty=RePaper Dock`, `product=(RePaper)`, `Color=F/T`, `Duplex=F`, `kind=document,label`, `adminurl=`, `note=`, `UUID=`. iOS will not list a printer without a valid `URF` key.
- **Media sizes are our secret weapon:** we advertise `media-supported` entries matching sheet sizes (e.g. `custom_repaper-card_84.8x63.6mm` for a 4.2" panel). Print dialogs then show "RePaper Card 4.2"" as a paper size, apps lay out for it, and the render is pixel-perfect without scaling. Default media = the default sheet size configured on the device.
- **Job states** map to the physical flow: job received → `pending` (LED green blink, `printer-state-reasons=media-needed` is a plausible signal "please hold a sheet"), transferring → `processing`, done → `completed`, timeout → `aborted` with a reason.

Reference implementations to learn from / borrow: PWG `ippsample` / CUPS `ippeveprinter` (C), the `libcups` IPP encoder/decoder, and CUPS + Avahi as a fallback for the Pi prototype (CUPS shares a queue via AirPrint out of the box; a custom CUPS *backend* would receive the rasterised pages and hand them to our transport — this is the fastest possible Dock prototype, at the cost of a heavier stack).

Networking gotchas to plan for: enterprise Wi-Fi often blocks mDNS across VLANs (offer manual "add printer by IP" and Wide-Area Bonjour/Unicast DNS-SD notes), Windows wants IPP Everywhere attributes exactly right, some clients insist on IPPS → self-signed cert support from day one.

## 2. Job queue & state

- In-memory + persisted (SQLite) queue; jobs survive a restart.
- Policy per device: *hold-until-tap* (default), *keep-queue* (many jobs, tap one after another, FIFO), *auto-send to named sheet* (enterprise, radio only).
- Timeouts, retries, and a single source of truth for the UI adapters (LED / app / web UI subscribe to the same state machine).

## 3. Renderer

- Decode: URF and PWG raster are simple run-length formats → easy. PDF needs a rendering engine: `pdfium` (best fidelity, big), `mupdf` (AGPL/commercial — careful), `poppler` (GPL — careful), or pure-Rust `pdf-render`/`hayro`-class crates (check maturity). Decision in the tech spike.
- Layout: page → sheet: fit / fill / rotate-to-best-fit / crop margins. Default: rotate if it increases scale, then fit with a white border of 0.
- Quantise: grayscale → sheet palette (BW, BWR, BWRY, 4-gray, 7-colour spectra). Error-diffusion dithering (Floyd–Steinberg / Atkinson) with a "text mode" threshold option so small text stays crisp.
- Output: packed bitplanes in the sheet's native layout, plus a PNG preview for the UI. Cache renders per (job, sheet model).

## 4. Sheet identification

How the device learns *which* sheet is being held to it:

- **NFC tag on the sheet** (most ESLs already have an NFC chip; we can also add a sticker tag). Tag holds a RePaper sheet ID → the local sheet registry resolves size, colour palette, radio address, firmware. Unknown tag → "new sheet" onboarding.
- **Proximity via radio** (BLE RSSI) as a fallback where NFC isn't available — less reliable, later.
- **Manual pick** from a list (Go app, web UI).

## 5. Display transports (pluggable)

| Transport | How the image gets to the sheet | Needs | Pros / cons |
|---|---|---|---|
| **NFC direct** | The reader powers and writes the panel through NFC (battery-less NFC e-paper) | PN532 / phone NFC | No battery, dead simple; slow (seconds to ~30 s for large panels), must hold still, limited sizes |
| **Open ESL firmware radio** | Device talks to an access point (e.g. ESP32 with the open AP firmware) which pushes the image to the tag over 2.4 GHz proprietary / 802.15.4 | AP module + flashed tags | Fast-ish, range across a room, battery years; requires flashing tags |
| **BLE** | Direct BLE image upload to sheets with a BLE SoC | Phone/Pi BLE | Works from phones without extra hardware; per-firmware protocol |
| **Manufacturer AP (SoluM)** | Dock talks to a SoluM AP / gateway API | Partner agreement | Stock tags, no flashing, enterprise-grade; dependency on partner API |

The transport interface is trivial by design: `send(sheet, bitmap) -> Result` plus `probe(sheet) -> {battery, firmware}`. Everything above it stays identical across Go and Dock.

## Security & enterprise readiness (baseline)

- LAN-only by default; IPPS with a self-signed or customer-provided certificate.
- No cloud dependency for printing. Cloud is outbound-only, TLS, device-scoped tokens.
- Job content is stored only until completed (configurable retention for audit).
- Firmware/app updates signed.

## Suggested repo layout (when we start coding)

```
core/        Rust: ipp, mdns, queue, render, sheets, transports (feature-flagged)
go/          Android app (Kotlin) + desktop app (Tauri) using core via UniFFI / FFI
dock/        Pi image build, daemon wiring, LED/button/NFC drivers, local web UI
cloud/       Fleet backend + console (later)
hardware/    Enclosure CAD, PCB, BOM
```
