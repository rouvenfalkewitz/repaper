# 11 · Shopping list — prototype v0

Prices are approximate (August 2026, Germany, incl. VAT). Links are to typical sellers; any equivalent is fine. Nothing here blocks the software — order and we build meanwhile.

## A. Order now (≈ €200 without phone)

| # | Part | Qty | ~€ | Where | Notes |
|---|---|---|---|---|---|
| 1 | Raspberry Pi 4 Model B, **2 GB is enough** (4 GB if in stock; Pi 5 2 GB also fine, needs its 27 W PSU) | 1 | 60–75 | [BerryBase](https://www.berrybase.de/en/raspberry-pi-4-computer-model-b-4gb-ram) · [reichelt](https://www.reichelt.com/de/en/shop/product/raspberry_pi_4_b_4x_1_5_ghz_4_gb_ram_wlan_bt-259920) · Welectron · rasppishop.de | Bench unit: Ethernet, BLE 5.0, PoE HAT later. (Pi 5 works too but needs a 5 A PSU + cooler for no benefit) |
| 2 | Official Raspberry Pi USB-C PSU 5.1 V / 3 A | 1 | 10 | same shop | |
| 3 | microSD 32 GB, A2 (SanDisk Extreme / Samsung Pro Plus) | 2 | 20 | Amazon / same shop | One per Pi |
| 4 | Raspberry Pi **Zero 2 WH** — *deferred, sold out; not needed for the bench prototype. Alternative: Zero 2 W + Pimoroni Hammer Header* | 0 | 22 | BerryBase / reichelt | The demo unit that goes in the enclosure; has BLE |
| 5 | Micro-USB PSU 5 V / 2.5 A (for the Zero) | 1 | 8 | same shop | |
| 6 | **PN532 NFC module V3** (Elechouse-type, I²C/SPI switchable) — *sold out at BerryBase; get 2× RC522 (MFRC522, SPI, ~2–3 cm range) today and a PN532 clone or Waveshare PN532 HAT from Amazon for the enclosure* | 2 | 8–12 each | [AZ-Delivery](https://www.az-delivery.de) · BerryBase · Amazon | One per Pi. Reads the sheet stickers |
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

## F. Learned on the bench (30 Aug 2026) — adjust before ordering

- **Coin cells, fresh and branded.** The label browned out mid-refresh at 2.67 V under load and came back half-drawn; a fresh cell (3.13 V) refreshes cleanly in ~20 s. Order a 10-pack of the type printed on the back of your tags (SoluM M3 family is mostly CR2450; Panasonic/Renata/Varta, not no-name). The Dock refuses to print below 2.7 V, so weak cells simply stop the demo.
- **Ask your mate for nRF52811-based tags for BWR.** Our 2.13" (nRF52811, fw 1.0.0) drives both colour planes correctly; the OpenDisplay findings note that ESP32/nRF52840 builds keep only one plane on BWR. For red on the demo sheets, prefer the nRF52811 models (or get the plane fix confirmed first).
- **A USB Bluetooth 5 dongle for the Pi (~€10–15).** Even the Mac's radio misses a tag advertisement every few scans; the Pi 4's onboard combo chip is weaker. A TP-Link UB500 / ASUS USB-BT500 (Realtek RTL8761B, in-kernel on Bookworm) or a dongle with an external antenna makes scans and the ~10 s connect reliable. Put it on a short USB extension away from the Pi's USB 3 ports (USB 3 noise kills 2.4 GHz).
- **NTAG213 stickers do double duty — no camera needed.** The sheet's OpenDisplay link (~58 characters, carries the key) fits in a 144-byte NTAG213. Registering a sheet on the Dock can write that link to the sticker on its back, so a tap on the PN532 identifies the sheet *and* hands over the key. Keep the 50-pack; skip any QR camera idea.
- **Sheet sizes for the demo.** The 2.13" label (128 addressed / 122 visible rows) is fine for tests but tiny for a "page"; get the 2.9" (296×128) and 4.2" (400×300) tags from your mate and keep the reTerminal E1001 (7.5") as the page-size piece. Every panel needs one calibration pass (hidden edge rows) — the Dock has a Calibrate flow for that now.
- **Pi OS Bookworm (64-bit) is required**, not Bullseye: the sidecar needs Python ≥ 3.11 and BlueZ ≥ 5.66 for bleak. Nothing to buy, but flash the right image.
- Unchanged: Pi 4B 4 GB + Zero 2 WH, PN532 V3 (I²C), WS2812 ring 16 + 74AHCT125, Pixel 9a as the Android phone.
