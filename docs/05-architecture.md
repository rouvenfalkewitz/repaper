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
                                                  │ 4. Sheet identification     │  ◀── nfc-sticker / qr / ble-rssi / manual
                                                  ├─────────────────────────────┤
                                                  │ 5. Sheet transport(s)       │  ──▶ opendisplay-ble / openepaperlink-ap / solum-* / nfc-direct / mock
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

## 5. Sheet transports — a hard interface boundary

**Rule: nothing above this line knows what a sheet physically is.** The first implementation talks OpenDisplay over BLE; a SoluM-specific path, an OpenEPaperLink access point, NFC-powered panels or a partner API must be addable as *another implementation of the same interface*, and several may be active at once. Every language we ever use for the core (Python sidecar now, Rust/Kotlin later) implements exactly this contract.

### The contract

```
SheetTransport                       one per physical path (opendisplay-ble, openepaperlink-ap, solum-x, nfc-direct, mock)
  id() -> "opendisplay-ble"
  discover() -> [SheetRef]           sheets reachable right now (BLE advertisements, AP tag list, …)
  describe(ref) -> SheetModel        width, height, palette (BW | BWR | BWRY | GRAY4 | …), orientation, refresh time
  status(ref) -> SheetStatus         battery, temperature, last seen, firmware — whatever the path can tell
  print(ref, page: Page, opts) -> PrintResult    page is ALREADY rendered for this sheet: an indexed bitmap in the
                                                 sheet's palette at its exact pixel size. The transport only transmits.
  capabilities() -> {supports_status, supports_partial_refresh, max_pixels, needs_pairing, …}

SheetIdentifier                      one per way of learning *which* sheet is being held to the printer
  id() -> "nfc-sticker" | "qr" | "ble-rssi" | "manual"
  wait_for_tap(timeout) -> SheetId   blocks until a sheet is presented (or the user picks one)

SheetRegistry                        the only place that maps identities to transports
  SheetId -> {name, model, transport_id, address, keys, last_status}
```

Design rules that make it replaceable:

1. **Rendering is above the line.** The core (PAPPL driver / renderer) turns the print job into a `Page` — fit, rotate, dither into the sheet's palette — using `describe()`. Transports never dither; if a path's SDK offers dithering (OpenDisplay's does), we pass the pre-dithered page through with dithering off. One renderer, identical output on every path.
2. **Addresses and keys are opaque** to the core: a BLE MAC + AES key, an AP tag MAC, a SoluM label ID — all just `address`/`keys` blobs owned by the transport that produced them.
3. **Identification is separate from transport.** An NFC sticker, a QR scan, "nearest by RSSI" or a manual pick all resolve to a `SheetId`; the registry says which transport delivers to it. Swapping the reader hardware never touches transports and vice versa.
4. **Status is best-effort and typed.** A path that can't report battery returns `unknown`, not a fake value; the UI and the cloud agent handle `unknown` explicitly.
5. **Every transport ships with a `mock`** that records what it was asked to print (to a PNG on disk) so the whole pipeline — IPP → render → tap → print — is testable on a laptop with no hardware.
6. **Selection by configuration**, not by build: the Dock loads the transports listed in its config; a sheet's registry entry names the one that delivers to it. Two Docks in one fleet may use different paths.

### Implementations, in order

| id | Path | Status |
|---|---|---|
| `mock` | writes the page as PNG, fakes status | first, for tests |
| `opendisplay-ble` | `py-opendisplay` over the Pi's / phone's BLE | prototype (v0) |
| `openepaperlink-ap` | HTTP `POST /imgupload` to an OpenEPaperLink access point | fallback for tags OpenDisplay doesn't cover |
| `solum-*` | whatever the SoluM partnership yields (their API, pre-flashed tags, a gateway) | when the partnership is concrete |
| `nfc-direct` | battery-less NFC-powered panels written through the reader itself | consumer sheets, later |

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
