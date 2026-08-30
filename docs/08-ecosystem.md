# 08 · Ecosystem check — what exists for each building block

Checked August 2026 before choosing a stack. The question: for each thing the core must do, what can we reuse, in which language, under what licence, and how alive is it?

## What the core must do

1. **Announce itself as a printer** — mDNS/DNS-SD with the AirPrint / IPP Everywhere / Mopria subtypes and TXT keys
2. **Speak IPP** — an HTTP server on 631 answering `Get-Printer-Attributes`, `Print-Job`, `Create-Job`/`Send-Document`, `Get-Jobs`, `Cancel-Job`, `Identify-Printer` with exactly the attribute set iOS, macOS, Windows and Android expect
3. **Receive documents** — Apple Raster (URF), PWG Raster, JPEG/PNG; PDF optional
4. **Render to a sheet** — scale, rotate, dither to BW / BWR at the panel's exact pixel size
5. **Send to the sheet** — via the open ESL access point (or NFC / SoluM later)
6. **Dock hardware** — NFC reader (PN532), RGB LED, button on a Raspberry Pi
7. **Run inside the Android app** later (the Go product)

## Findings

### 1 + 2 · Printer emulation (the risky part)

| Option | What it is | Status | Licence | Verdict |
|---|---|---|---|---|
| **PAPPL** (C) | Michael Sweet's *Printer Application Framework*: embedded IPP Everywhere server, DNS-SD, AirPrint + Mopria conformance, PWG & Apple raster decoding, job management, built-in web UI. Runs on Linux/macOS/Windows, tested on Raspberry Pi Zero W. This is the framework OpenPrinting uses to turn drivers into "printer applications". | Active, ~1.8k commits | Apache-2.0 (+ LGPL linking exception) | **The reference implementation of exactly what we're pretending to be.** We write a small "driver" callback; the framework does the protocol. |
| `ippper` (Rust) | Library for simple IPP servers on tokio/hyper; "enough to fool Android Printer Service & Windows Print Spooler". No mDNS, no AirPrint TXT. | v0.6.0, June 2026 | BSD-3 | Usable base; AirPrint attribute surface would be ours to build. |
| `ipp` (Rust) | IPP codec (RFC 8010/8011), client-focused, parsing usable server-side | active, 500+ commits | MIT/Apache | Good codec, not a server. |
| `product-os-print-server` (Rust) | IPP 1.1 server + zeroconf | v0.0.2, July 2026 | **GPL-3** | Avoid (licence, heavy deps). |
| `ipp-server` (Rust) | server helpers | 2019, dead | MIT/Apache | No. |
| `goipp` (Go, OpenPrinting) | IPP codec in pure Go, used by `ipp-usb` | active | BSD-2 | Solid codec. |
| `go-mfp` (Go, OpenPrinting) | "behaviour-accurate MFP simulator": IPP 2.x, DNS-SD, eSCL/WSD; usable as libraries | WIP, 2.5k commits | BSD-2 | Promising but explicitly unfinished. |
| `ipp-printer` (Node) | IPP 1.1 printer with Bonjour, writes jobs to disk | old, inactive | MIT | Demo-grade; no AirPrint (URF). |
| `ippserver` (Python) | "enough IPP to fool CUPS" | minimal | — | Throwaway demos only. |
| `ippeveprinter` (CUPS, C) | Reference IPP Everywhere printer that runs a command per job | maintained with CUPS | Apache-2.0 | Great for *testing* and a fallback prototype (it already does mDNS + AirPrint). |

### DNS-SD

- Rust **`mdns-sd`** 0.21 (Aug 2026): pure Rust, registers services with TXT records, supports subtypes (`_universal._sub._ipp._tcp`). Apache/MIT. Good.
- Go `grandcat/zeroconf`: last release 2020, unmaintained. Alternatives exist (`brutella/dnssd`) but none is the obvious choice.
- C: Avahi / Bonjour, handled by PAPPL.

### 3 · Document formats

- **URF (Apple Raster) and PWG Raster are simple** — a header plus run-length-encoded lines. Reference code: `ppm2pwg`, `rasterview`, PAPPL's decoders. Implementing a decoder ourselves is a few hundred lines in any language.
- **PDF is optional.** Driverless clients rasterise for the printer: iOS/macOS send URF, Windows (IPP class driver) and Android (Mopria) send PWG raster. If we advertise only `image/urf`, `image/pwg-raster`, `image/jpeg`, `image/png` we never have to render a PDF. That removes the heaviest dependency from v1.
- If/when we want PDF: Rust `pdfium-render` 0.9.x (mature, needs the pdfium binary; MIT/Apache + BSD), Rust `hayro` (pure Rust, early stage, "not production" yet), Python `pypdfium2`, Go `go-pdfium`. Avoid mupdf/`go-fitz` (AGPL) and poppler (GPL).

### 4 · Rendering / dithering

Trivial everywhere: Rust `image`, Go `image`, Python Pillow, C in PAPPL. Ordered/error-diffusion dithering is ~50 lines; we already have the Bayer logic from the brand work.

### 5 · Sending to sheets — OpenDisplay (and OpenEPaperLink as the alternative)

**OpenDisplay** (https://opendisplay.org, the friend's project): open standard + GPL-3 firmware; the sender pushes a dithered image **directly over BLE** (GATT service 0x2446) — no gateway. `py-opendisplay` (MIT, `bleak`) gives discovery, keyed connect, `upload_image(PIL)` with fit + nine dither modes, and battery/temperature from advertisements; Linux/Raspberry Pi fully supported, protocol documented for a Kotlin/Rust port later. Runs on re-flashed SoluM M3 tags (nRF52811 2.7"/2.9", EFR32BG22 2.6"/3.5" today) and on nRF52840/ESP32 boards, incl. Seeed's ready-made receivers. This is the prototype transport.

**OpenEPaperLink** (the other open ESL firmware): needs an ESP32 access point with a flashed tag as 802.15.4 radio; `POST /imgupload` with tag MAC + baseline JPEG at the exact size (296×128, 400×300, 152×152). Kept as the fallback for tag models OpenDisplay doesn't cover yet.

### 6 · Dock hardware

- NFC PN532: Rust `pn532` (pure Rust on `embedded-hal`, works with `linux-embedded-hal` on the Pi), Python `pn532pi` / `nfcpy`, C `libnfc`. All fine.
- RGB LED / button: GPIO libraries everywhere (`rppal` in Rust, `gpiozero` in Python, `libgpiod` in C).

### 7 · Android embedding

- Rust → Kotlin via **UniFFI** + `cargo-ndk`: mature, used by Mozilla in Firefox; Gradle plugins exist. Best story of the three.
- Go via `gomobile`: works, clunkier.
- C (PAPPL) on Android: not a supported platform; the app needs its own IPP listener anyway (foreground service + `NsdManager`), which is small in Kotlin.

## What this changes

My earlier "Rust core + thin shells" recommendation assumed we'd have to write the printer emulation ourselves in whatever language we chose. We don't. **PAPPL already is the product's hardest part**, battle-tested against the exact clients we must satisfy, on the exact hardware we plan to use, under a licence we can ship. The risk in RePaper isn't rendering or NFC — it's the hundred small IPP/AirPrint compatibility details, and PAPPL has spent years on them.

### Recommendation

**Two tracks, in this order.**

**Track 1 — Dock (now): PAPPL.** Build the Dock as a *printer application*: PAPPL handles DNS-SD, IPP, AirPrint/Mopria conformance, raster decoding, job states and a setup web UI. Our own code is small and specific:
- a PAPPL driver whose "print a page" callback takes the decoded raster, dithers it to the sheet palette and hands it to the transport;
- a transport module that pushes the dithered image over BLE with `py-opendisplay` (later: Wi-Fi LAN receivers, NFC);
- a small sidecar (Python at first, later Rust or C) for PN532 tap detection and the LED, talking to the printer app over a local socket;
- our sheet sizes advertised as media sizes; job stays "pending — media needed" until a tap.
Language: C99 for the ~500 lines of callbacks. Neither of us is a "C person", but this is glue against a clean API, not systems programming, and it buys us a conformant AirPrint printer in about a week instead of a month.

**Track 2 — Go app (later): Rust core via UniFFI, or Kotlin-native.** Android can't run PAPPL. By then we'll know the exact IPP attribute set we need (we can lift it from our PAPPL config), and the Android IPP listener is a small, well-defined piece. Decide Rust-vs-Kotlin when we start it; nothing in track 1 forecloses either.

**Prototype-first alternative** if a demo in days matters more than a foundation: `ippeveprinter` (ships with CUPS) with a Python command that takes the PWG raster, dithers it and pushes it over BLE with `py-opendisplay` — a working "print to e-paper" demo with almost no code, and everything learned carries over to the PAPPL build.

**Not recommended:** a from-scratch IPP server in Rust/Go for v1 (we'd re-solve PAPPL's problems), anything GPL/AGPL in the core, PDF rendering in v1.

## Sources

- PAPPL — https://github.com/michaelrsweet/pappl
- ippeveprinter — https://www.cups.org/doc/man-ippeveprinter.html · IPP sample — https://istopwg.github.io/ippsample/
- ippper — https://lib.rs/crates/ippper · ipp.rs — https://github.com/ancwrd1/ipp.rs · ipp-server — https://lib.rs/crates/ipp-server · product-os-print-server — https://lib.rs/crates/product-os-print-server
- goipp — https://pkg.go.dev/github.com/OpenPrinting/goipp · go-mfp — https://github.com/OpenPrinting/go-mfp
- ipp-printer (Node) — https://github.com/watson/ipp-printer · ippserver (Python) — https://pypi.org/project/ippserver/
- mdns-sd — https://docs.rs/mdns-sd/latest/mdns_sd/ · grandcat/zeroconf — https://pkg.go.dev/github.com/grandcat/zeroconf
- Driverless standards & PDLs — https://openprinting.github.io/driverless/01-standards-and-their-pdls/ · ppm2pwg — https://github.com/attah/ppm2pwg · rasterview — https://github.com/michaelrsweet/rasterview
- pdfium-render — https://crates.io/crates/pdfium-render · hayro — https://github.com/LaurenzV/hayro
- OpenDisplay — https://opendisplay.org · protocol — https://opendisplay.org/protocol/ · firmware — https://github.com/OpenDisplay/Firmware · py-opendisplay — https://github.com/OpenDisplay/py-opendisplay · SoluM M3 — https://opendisplay.org/firmware/reusing_solum_displays.html · Seeed receivers — https://wiki.seeedstudio.com/EN04_opendisplay/
- OpenEPaperLink — https://github.com/OpenEPaperLink/OpenEPaperLink · image upload — https://github.com/OpenEPaperLink/OpenEPaperLink/wiki/Image-upload · image specs — https://github.com/OpenEPaperLink/OpenEPaperLink/wiki/Image-specifications
- pn532 (Rust) — https://crates.io/crates/pn532 · UniFFI — https://github.com/mozilla/uniffi-rs
