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

**v0: an OpenEPaperLink access point as a separate module.** The standard AP is an ESP32 with a flashed SoluM tag acting as the 2.4 GHz radio, talking to the ESP32 over UART; the AP joins Wi-Fi and exposes `POST /imgupload` (tag MAC + JPEG at the tag's exact size). The Dock talks to it over the LAN (or, in the enclosure, over USB serial / a private Wi-Fi). Later revisions can put the ESP32 + radio on our own PCB next to the Pi. Ask the friend which AP board he recommends — there are several community boards, and he may sell or lend one.

## Bill of materials — prototype v0

| # | Part | Choice | ~€ | Notes |
|---|---|---|---|---|
| 1 | Compute (dev bench) | Raspberry Pi 4 B 2 GB (or Pi 5) | 55 | Ethernet + USB for the first weeks; PAPPL builds natively |
| 2 | Compute (demo unit) | Raspberry Pi Zero 2 W | 20 | Wi-Fi only; PAPPL runs on a Zero W. Pilot units with Ethernet/PoE: Pi 4 + PoE HAT, or CM4/CM5 on a carrier later |
| 3 | Storage | microSD 32 GB, A2 class | 10 | |
| 4 | NFC reader | PN532 breakout (Elechouse V3 or Adafruit), I²C or SPI | 8–12 | Reads NTAG stickers; antenna sits under the Dock's top surface |
| 5 | Sheet stickers | NTAG213 (144 B) stickers, 25 mm, ×50 | 8 | Sheet ID as NDEF text/URL |
| 6 | Sheet radio | OpenEPaperLink AP (ESP32 + flashed tag as radio) | 20–40 | Via the friend; or build from an ESP32 dev board + one tag |
| 7 | Sheets | SoluM 2.9" (296×128) ×3, 4.2" (400×300) ×3, 7.5" (800×480) ×1, BWR | 10–30 each | Must be OpenEPaperLink-compatible models; factory-locked units need a J-Link to flash — ask for pre-flashed |
| 8 | Light ring | WS2812B ring, 16 LEDs (Ø ~68 mm) | 6 | Driven from the Pi (PWM/SPI). **This is the brand's ring, for real** |
| 9 | Diffuser | Frosted acrylic ring or translucent-white printed part | 5 | The logo's silhouette sits behind it |
| 10 | Button | Momentary, 12 mm | 2 | Confirm / cancel / long-press reset |
| 11 | Buzzer | Passive piezo | 1 | Optional |
| 12 | Power | USB-C 5 V / 3 A PSU | 10 | PoE HAT (~25) for Pi 4 pilot units |
| 13 | Enclosure | 3D-printed, matte carbon PETG/ASA; 28 px-radius silhouette scaled to ~110 mm | 10 | NFC zone marked on top; ring channel; vents |
| 14 | Cables, headers, standoffs | | 10 | |
| | **Total (one bench + one demo unit, 7 sheets)** | | **≈ 300–400** | J-Link (~€60 EDU) only if we must flash locked tags ourselves |

## Physical layout (demo unit)

```
        ┌──────────────────────┐
        │   ◯  light ring      │  top: frosted ring, NFC zone in the centre ("hold a sheet here")
        │      [ NFC ]         │
        └──────────────────────┘
        │ Pi Zero 2 W │ ESP32 AP + radio tag │ PN532 under the lid │ button on the side │ USB-C at the back
```

Ring: breathing green = ready · blinking = job waiting · fast = printing · solid 3 s = printed · amber / red as in the brand guide's LED table.

## Software split on the Dock

```
PAPPL printer application (C)   ── IPP/AirPrint/Mopria, DNS-SD, raster, jobs, web UI
   └─ RePaper driver            ── raster → dither → JPEG → OpenEPaperLink AP (HTTP)
sidecar (Python first)          ── PN532 tap → sheet ID → tells PAPPL which job to release; drives the ring + button
cloud agent                     ── outbound MQTT/WebSocket: heartbeat, status, config, OTA (see 10-cloud-platform.md)
```

## What to order now

Items 1–5, 8–14 from any Pi retailer today. Items 6–7 via the friend (which AP board, which tag models, pre-flashed?). Sheet sizes for the demo: 2.9" and 4.2" in BWR; one 7.5" if available.

## Open

- Tag models & NFC chip types (A2) — friend
- AP board & firmware licence — friend
- Pilot units: Pi 4 + PoE HAT vs. CM4 carrier — after the demo
- Custom PCB (ESP32 + radio + PN532 + LED driver on one board, Pi/CM as compute) — Phase 4
