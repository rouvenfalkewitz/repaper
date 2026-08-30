# 06 · E-paper hardware (parked — decide later)

We deliberately keep the sheet hardware open for now; the core is transport-agnostic. This note collects the options so the decision is easy when we get to it.

## Options on the table

1. **SoluM ESL tags via partner relationship**
   - Pros: enterprise-grade, many sizes/colours, existing AP/gateway infrastructure, battery life in years, mass-produced, NFC on most models.
   - Cons: image push normally goes through SoluM's AP + server stack (we'd need API access or an SDK agreement); stock firmware is proprietary.
   - Ideal for: Dock in enterprise, "named sheet" mode, large rollouts.

2. **OpenDisplay — the friend's project (https://opendisplay.org)** ← prototype choice
   - An *open standard* plus open firmware (GPL-3) for e-paper receivers: the sender (app, Home Assistant, our Dock) renders and dithers, then pushes the image **directly over Bluetooth LE** (GATT service 0x2446) — **no gateway, no access point, no cloud**. Optional Wi-Fi LAN mode with the same command set on ESP32-based receivers.
   - Works on re-flashed **SoluM M3 tags** (nRF52811: 2.7" and 2.9" compatible; EFR32BG22: 2.6" and 3.5" compatible, 1.6"–11.6" in progress) and on new boards (nRF52840, ESP32-S3/C3/C6) with off-the-shelf panels, incl. Seeed's ready-made receivers (reTerminal E1001 7.5" 800×480 mono, E1002 7.3" Spectra 6, E1003 10.3", XIAO 4.26").
   - Each display shows a QR that links to a page carrying its name, AES-128 key and manufacturer ID — the QR *is* the pairing credential; a browser can push an image via Web Bluetooth right away.
   - **Python SDK** (`py-opendisplay`, MIT, on `bleak`): discovery, connect with key, `upload_image(PIL)` with fit modes and nine dither algorithms, battery/temperature from advertisements. Linux/Raspberry Pi fully supported.
   - Flashing SoluM tags needs a genuine J-Link (V9+); hardware varies between batches — prefer pre-flashed tags from the friend.
   - **Field notes from the first label (SoluM 2.1" BWR, nRF52811, firmware 1.0.0, 30 Aug 2026):** (a) firmware 1.x direct-write accepts only one bit plane on BWR/BWY tags (documented in py-opendisplay FINDINGS C1) — the Dock sends such sheets as mono with red drawn black until firmware parity; (b) **a refresh on a weak cell browns the tag out**: at 2.67 V the tag dropped the BLE link mid-refresh (no REFRESH_COMPLETE), left a half-cleared panel, reset, and read 2.25 V afterwards; with a fresh cell (3.13 V) the same upload completed in 20 s with REFRESH_COMPLETE. The Dock reads the battery from the advertisement, refuses to print below 2.7 V, and still treats "refresh started, then link dropped" as printed as a safety net. Questions for the friend: BWR two-plane support, recommended low-battery cut-off.
   - Ideal for: the Dock (Pi has BLE), the Go app (phone has BLE), the demo, and as the sheet standard we build on. BLE's room-scale range matches the product principle exactly: the sheet is printed by the printer it is held to.

3. **Battery-less NFC-powered e-paper panels** (e.g. Waveshare-style NFC e-paper, 2.13"–7.5")
   - Powered entirely by the NFC field; the phone/Dock writes the image directly. Perfectly matches "hold it here and it prints". Zero battery, zero radio infrastructure, cheapest sheet.
   - Cons: slow updates, must hold still, no remote update, small/medium sizes only, needs a strong NFC field (Pi with PN532 may need antenna tuning; phones vary).
   - Ideal for: consumer Go use, demos, badges/cards.

4. **Custom sheet later** (own PCB around a panel: NFC + BLE + optional 802.15.4, our case, our mount points)
   - The long-term "RePaper Sheet" product. Only after we know what sells.

## Likely path

- **Prototype:** option 2 (OpenDisplay over BLE) for both Dock and Go — no gateway hardware at all. Option 3 (NFC-powered panel) only if we want a battery-less consumer sheet later.
- **Pilot:** talk to SoluM about sourcing unlocked / pre-flashed tags once the demo exists (no API deal needed — OpenDisplay replaces it).
- **Product:** mix — SoluM for enterprise, own/NFC sheets for consumer & cards.

## Sheet metadata we need per model (for the registry)

`model id, diagonal, pixel width × height, palette (BW/BWR/BWRY/gray/colour), physical active area (mm), refresh time, transport, NFC tag type, battery (y/n), mounting`.

## Accessory ideas (the ecosystem)

- Magnetic / clip / slide-in mounts for warehouse carts, shelving, racks, doors, monitors.
- Lanyard & badge holders; table-tent stands; fridge magnets.
- Protective cases in brand colours; IP-rated cases for industry.
- Charging / sync trays that also act as a Dock (a tray with an antenna: drop 10 sheets in, print 10 pages).
