# 09 · Dock hardware — prototype v0

What we build first, what it costs, and the two hardware decisions that shape the software.

## The two decisions

### A. What does "hold a sheet to the Dock" actually read?

The Dock needs to know *which* sheet is in front of it. Options:

| Option | How | Pro | Con |
|---|---|---|---|
| **A1 · NFC sticker on every sheet** (recommended for v0) | An NTAG213/215 sticker (ISO 14443-A, ~€0.15) on the back of each sheet holds the sheet ID; the Dock's PN532 reads it | Works with every tag model regardless of its own NFC chip; cheapest reader; cannot fail because of tag firmware; we control the ID scheme | One sticker per sheet at assembly |
| A2 · The tag's built-in NFC | Many SoluM tags carry an NFC chip; read its UID | No extra part | Chip type varies by model (some are ISO 15693 / NFC-V, which the PN532 cannot read → needs a PN5180 or ST25R391x reader); behaviour under the open firmware unconfirmed |
| A3 · Radio proximity (RSSI) | Pick the tag with the strongest signal | No NFC at all | Unreliable with several sheets nearby; not the "hold it here" gesture |

**v0: A1.** Confirm A2 with the firmware friend for a later revision (it would remove the sticker).

### B. How does the Dock talk to the sheets?

**v0: directly over Bluetooth LE, using OpenDisplay** (https://opendisplay.org — the friend's open standard/firmware, see `06-epaper-hardware.md`). The Pi's own BLE radio pushes the dithered image straight to the sheet with `py-opendisplay`; no access point, no ESP32 module, no second Wi-Fi network. BLE range is a room — and that is the product, not a limitation: **a sheet is printed by the printer it is held to**, the way paper comes out of the tray in front of you. There is no remote "update sheet 12 on the other side of the building" feature, and none is planned.

Sheet identification on top of that: BLE advertisements carry name, manufacturer ID, battery and temperature, so the Dock always knows which sheets are in range; the NFC sticker (A1) or the sheet's own QR code says which one is *being held to it*.

## Bill of materials — prototype v0

| # | Part | Choice | ~€ | Notes |
|---|---|---|---|---|
| 1 | Compute (dev bench) | Raspberry Pi 4 B 2 GB (or Pi 5) | 55 | Ethernet + USB for the first weeks; PAPPL builds natively |
| 2 | Compute (demo unit) | Raspberry Pi Zero 2 W | 20 | Wi-Fi only; PAPPL runs on a Zero W. Pilot units with Ethernet/PoE: Pi 4 + PoE HAT, or CM4/CM5 on a carrier later |
| 3 | Storage | microSD 32 GB, A2 class | 10 | |
| 4 | NFC reader | PN532 breakout (Elechouse V3 or Adafruit), I²C or SPI | 8–12 | Reads NTAG stickers; antenna sits under the Dock's top surface |
| 5 | Sheet stickers | NTAG213 (144 B) stickers, 25 mm, ×50 | 8 | Sheet ID as NDEF text/URL |
| 6 | Sheet radio | — none — | 0 | The Pi's built-in BLE talks OpenDisplay directly |
| 7 | Sheets | SoluM M3 2.9" (nRF52811) ×3 and 2.6"/3.5" (EFR32BG22) ×2 with OpenDisplay firmware; one Seeed reTerminal E1001 7.5" as the "page" | 10–30 each (E1001 more) | Ask the friend for pre-flashed tags; larger SoluM sizes are still work-in-progress in OpenDisplay |
| 8 | Light ring | WS2812B ring, 16 LEDs (Ø ~68 mm) | 6 | Driven from the Pi (PWM/SPI). **This is the brand's ring, for real** |
| 9 | Diffuser | Frosted acrylic ring or translucent-white printed part | 5 | The logo's silhouette sits behind it |
| 10 | Button | Momentary, 12 mm | 2 | Confirm / cancel / long-press reset |
| 11 | Buzzer | Passive piezo | 1 | Optional |
| 12 | Power | USB-C 5 V / 3 A PSU | 10 | PoE HAT (~25) for Pi 4 pilot units |
| 13 | Enclosure | 3D-printed, matte carbon PETG/ASA; 28 px-radius silhouette scaled to ~110 mm | 10 | NFC zone marked on top; ring channel; vents |
| 14 | Cables, headers, standoffs | | 10 | |
| | **Total (one bench + one demo unit, ~6 sheets)** | | **≈ 250–400** | J-Link (~€60 EDU) only if we must flash locked tags ourselves |

## Physical layout (demo unit)

```
        ┌──────────────────────┐
        │   ◯  light ring      │  top: frosted ring, NFC zone in the centre ("hold a sheet here")
        │      [ NFC ]         │
        └──────────────────────┘
        │ Pi Zero 2 W (BLE) │ PN532 under the lid │ button on the side │ USB-C at the back
```

Ring: breathing green = ready · blinking = job waiting · fast = printing · solid 3 s = printed · amber / red as in the brand guide's LED table.

## Software split on the Dock

```
PAPPL printer application (C)   ── IPP/AirPrint/Mopria, DNS-SD, raster, jobs, web UI
   └─ RePaper driver            ── hands the decoded raster to the sidecar
sidecar (Python)                ── fit + dither + BLE push via py-opendisplay; PN532 tap → sheet ID → releases the job; drives the ring + button
cloud agent                     ── outbound MQTT/WebSocket: heartbeat, status, config, OTA (see 10-cloud-platform.md)
```

## What to order now

Items 1–5, 8–14 from any Pi retailer today. Item 7 via the friend (which tag models, pre-flashed?) plus one Seeed reTerminal E1001 from Seeed. Sheet sizes for the demo: 2.9" (label) and 2.6"/3.5" (card) in BWR, 7.5" (page) mono.

## Open

- Tag models & NFC chip types (A2) — friend
- Encryption-key model for fleets (the QR carries the key today) and firmware licence for a commercial product — friend
- Pilot units: Pi 4 + PoE HAT vs. CM4 carrier — after the demo
- Custom PCB (ESP32 + radio + PN532 + LED driver on one board, Pi/CM as compute) — Phase 4

## Enclosure v0 — spec for the 3D print (Pi 3 A+ pilot unit)

The case is the product: a small pedestal you hold a sheet against. Function over looks for v0.

**Layout (bottom to top):** Pi 3 A+ on M2.5 bosses (hole pattern 58 × 49 mm, board 65 × 56 mm, ~14 mm tall with header) → reader board → top plate. LED ring recessed in the top plate, shining upward through a diffuser.

**Hard constraints**
- NFC reader sits directly under the top plate; wall above the antenna **≤ 2 mm** (RC522 only has ~2–3 cm range through air; every millimetre of plastic costs range). No metal, no screws, no LED-ring wiring within ~10 mm of the antenna area.
- Top plate: flat rest area ≥ 120 × 120 mm so a 4.2" tag lies fully supported; the glowing ring is the "hold it here" marker, centred on the antenna.
- LED ring: 16× WS2812, ~68 mm outer Ø. Recess with a 1–2 mm translucent diffuser above (white PETG/PLA printed at 2 perimeters works). Glow is light, never ink — the ring is the only light on the device.
- Port access: micro-USB power and the USB-A (BLE dongle) must exit cleanly; microSD reachable without opening the case is a plus for v0.
- Ventilation slots on the sides/bottom; the A+ is passively fine but sealed boxes cook radios.
- Material: plain PLA or PETG. **No carbon-fibre or "conductive" filament** anywhere near the antenna.
- Colour: Carbon-dark body if available; the diffuser ring stays white/natural (the green comes from the LEDs).

**Nice to have:** cable channel from Pi to reader, snap-fit or 4 self-tapping screws from below, a v0 without the ring cutout printed first just to validate NFC range through the actual wall.
