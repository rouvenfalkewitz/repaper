# 06 · E-paper hardware (parked — decide later)

We deliberately keep the sheet hardware open for now; the core is transport-agnostic. This note collects the options so the decision is easy when we get to it.

## Options on the table

1. **SoluM ESL tags via partner relationship**
   - Pros: enterprise-grade, many sizes/colours, existing AP/gateway infrastructure, battery life in years, mass-produced, NFC on most models.
   - Cons: image push normally goes through SoluM's AP + server stack (we'd need API access or an SDK agreement); stock firmware is proprietary.
   - Ideal for: Dock in enterprise, "named sheet" mode, large rollouts.

2. **Tags running the open firmware (friend's project)**
   - Many ESLs (incl. SoluM models) can be re-flashed with an open firmware and driven from a cheap ESP32-based access point. This is our fastest path to a working prototype with no partner dependency.
   - Needs confirmation: which tag models / sizes, transfer speed per image, NFC behaviour on the flashed tags, licence of the firmware, and whether flashing is feasible at scale (or whether the manufacturer could ship pre-flashed).
   - Ideal for: prototypes, Go app via a small AP dongle, and possibly production if the friend joins / licenses.

3. **Battery-less NFC-powered e-paper panels** (e.g. Waveshare-style NFC e-paper, 2.13"–7.5")
   - Powered entirely by the NFC field; the phone/Dock writes the image directly. Perfectly matches "hold it here and it prints". Zero battery, zero radio infrastructure, cheapest sheet.
   - Cons: slow updates, must hold still, no remote update, small/medium sizes only, needs a strong NFC field (Pi with PN532 may need antenna tuning; phones vary).
   - Ideal for: consumer Go use, demos, badges/cards.

4. **Custom sheet later** (own PCB around a panel: NFC + BLE + optional 802.15.4, our case, our mount points)
   - The long-term "RePaper Sheet" product. Only after we know what sells.

## Likely path

- **Prototype:** option 2 (open firmware + ESP32 AP) for Dock, plus option 3 (NFC-powered panel) for the Go app demo — both are buyable this week.
- **Pilot:** talk to SoluM about API access / pre-flashed tags once the demo exists.
- **Product:** mix — SoluM for enterprise, own/NFC sheets for consumer & cards.

## Sheet metadata we need per model (for the registry)

`model id, diagonal, pixel width × height, palette (BW/BWR/BWRY/gray/colour), physical active area (mm), refresh time, transport, NFC tag type, battery (y/n), mounting`.

## Accessory ideas (the ecosystem)

- Magnetic / clip / slide-in mounts for warehouse carts, shelving, racks, doors, monitors.
- Lanyard & badge holders; table-tent stands; fridge magnets.
- Protective cases in brand colours; IP-rated cases for industry.
- Charging / sync trays that also act as a Dock (a tray with an antenna: drop 10 sheets in, print 10 pages).
