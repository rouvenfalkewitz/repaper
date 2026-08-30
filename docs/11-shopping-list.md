# 11 · Shopping list — prototype v0

Prices are approximate (August 2026, Germany, incl. VAT). Links are to typical sellers; any equivalent is fine. Nothing here blocks the software — order and we build meanwhile.

## A. Order now (≈ €200 without phone)

| # | Part | Qty | ~€ | Where | Notes |
|---|---|---|---|---|---|
| 1 | Raspberry Pi 4 Model B, **4 GB** | 1 | 60–75 | [BerryBase](https://www.berrybase.de/en/raspberry-pi-4-computer-model-b-4gb-ram) · [reichelt](https://www.reichelt.com/de/en/shop/product/raspberry_pi_4_b_4x_1_5_ghz_4_gb_ram_wlan_bt-259920) · Welectron · rasppishop.de | Bench unit: Ethernet, BLE 5.0, PoE HAT later. (Pi 5 works too but needs a 5 A PSU + cooler for no benefit) |
| 2 | Official Raspberry Pi USB-C PSU 5.1 V / 3 A | 1 | 10 | same shop | |
| 3 | microSD 32 GB, A2 (SanDisk Extreme / Samsung Pro Plus) | 2 | 20 | Amazon / same shop | One per Pi |
| 4 | Raspberry Pi **Zero 2 WH** (header pre-soldered) | 1 | 22 | BerryBase / reichelt | The demo unit that goes in the enclosure; has BLE |
| 5 | Micro-USB PSU 5 V / 2.5 A (for the Zero) | 1 | 8 | same shop | |
| 6 | **PN532 NFC module V3** (Elechouse-type, I²C/SPI switchable) | 2 | 8–12 each | [AZ-Delivery](https://www.az-delivery.de) · BerryBase · Amazon | One per Pi. Reads the sheet stickers |
| 7 | **NTAG213 NFC stickers**, 25 mm round, 50 pcs | 1 | 10 | Amazon / NFC-Tag-Shop.de | One on the back of every sheet |
| 8 | **WS2812B LED ring, 16 LEDs**, ~68 mm | 2 | 5–8 each | AZ-Delivery · BerryBase · Adafruit "NeoPixel Ring 16" (~€12) | The light ring |
| 9 | 74AHCT125 level shifter (or Adafruit NeoPixel level-shifter breakout) | 1 | 2–5 | reichelt · BerryBase | 3.3 V → 5 V data; avoids flaky LEDs |
| 10 | Tactile buttons 12 mm (pack) + passive piezo buzzer | 1 | 5 | AZ-Delivery / Amazon | |
| 11 | Breadboard, jumper wires (M-F + F-F), GPIO breakout / Perma-Proto HAT, M2.5 standoffs | 1 | 20 | BerryBase / Amazon | |

## B. The Android test phone

**Google Pixel 9a, 128 GB — ≈ €365–380 new** ([Geizhals](https://geizhals.de/google-pixel-9a-v193689.html); UVP €549). Why this one: stock Android with the newest APIs, NFC reader mode, BLE 5.3, long update support, and it's the reference device most Android developers test on. If budget matters, a **refurbished Pixel 8a** (~€220–280 via Back Market / refurbed) is equally good for our purposes. Avoid phones without NFC.

## C. Sheets — not from a shop

- **From your mate:** SoluM M3 tags with OpenDisplay already flashed — ideally 3× 2.9" (nRF52811, "label") and 2× 2.6"/3.5" (EFR32BG22, "card"), BWR if available. Ask what he recommends; his compatibility list is the truth.
- **"Page" size, optional for now:** Seeed **reTerminal E1001** (7.5", 800×480, OpenDisplay-ready) — **$79** at [Seeed Studio](https://www.seeedstudio.com/reTerminal-E1001-p-6534.html), also on AliExpress (~$74) and Amazon; EU distributors: Mouser, DigiKey, OpenELAB. Great for the pitch; not needed for the first demo.

## D. Don't buy

- An OpenEPaperLink access point — not needed with OpenDisplay.
- A J-Link — let the friend flash; genuine ones are €60+ and clones are unreliable.
- A frosted diffuser — we 3D-print it translucent.
- Pi 5 / PoE HATs / CM4 — pilot-stage decisions.

## E. Later (pilot)

PoE HAT for the Pi 4 (~€25), a second Pi 4 for a second Dock, 3D printing service for the enclosure if no printer at hand, more sheets.
