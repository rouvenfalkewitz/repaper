# 04 · Product 2 — RePaper Dock (the device)

## Summary

A small always-on box that lives on the customer's network as a printer. Print to it from anything; an LED tells you a job is waiting; hold a sheet to the Dock; the sheet updates; the LED confirms; the Dock is ready again.

Dock is the product for businesses: it needs no phone, no person's account, and it can be managed as a fleet.

## Prototype hardware (Raspberry Pi)

| Part | Choice for prototype | Notes |
|---|---|---|
| Compute | Raspberry Pi Zero 2 W (or Pi 4/5 for dev comfort) | Wi-Fi + Ethernet (Pi 4/5) for enterprise networks |
| NFC reader | PN532 module (I²C or SPI) | Reads the sheet's NFC tag to identify which sheet is held to the Dock; can also *write* to NFC-powered e-paper directly |
| Sheet radio | Depends on sheet hardware: ESP32 running the open ESL AP firmware over USB/serial, or a manufacturer AP (SoluM) | See `06-epaper-hardware.md` |
| LED | One RGB LED (WS2812 or discrete) behind a diffuser | The entire UI of the device |
| Button | One push button | Confirm / cancel / factory reset (long press) |
| Buzzer | Optional piezo | Audible "job waiting" in loud environments |
| Enclosure | 3D printed, flat top with a marked "place sheet here" area | The top surface is the NFC antenna zone |
| Power | USB-C 5V; PoE HAT variant later | |

Target later: custom PCB (e.g. ESP32-S3 or a small Linux SoM) once the software is stable — but Pi first.

## LED language

| State | LED | Meaning |
|---|---|---|
| Booting / connecting | Blue pulsing | |
| Ready (idle) | Soft white breathing (or brand green, dim) | Printer is online and empty |
| Setup mode | Blue blinking | Access point active / waiting for onboarding |
| **Job waiting** | **Green blinking** | Hold a sheet to print |
| Transferring | Green fast blink / chase | Keep the sheet in place |
| **Success** | **Solid green 3 s → ready** | Job completed |
| Sheet mismatch / warning | Amber | e.g. sheet too small for the page; press button to force fit or cancel |
| Error | Red blink | Transfer failed — hold again to retry; long press to cancel |
| No network | Red solid | |

Multi-page jobs: after each successful page the LED returns to green blinking with a short double-flash → hold the next sheet.

## User flow

1. **Unbox & connect**: power on → Dock opens a Wi-Fi hotspot "RePaper-Dock-XXXX" (or just use Ethernet) → phone opens the setup page → pick Wi-Fi, name the printer, optionally claim it into RePaper Cloud with a QR code.
2. **Print**: the Dock appears on the network as "RePaper Dock – Warehouse A". Print from anything.
3. **LED blinks green** → someone holds a sheet to the Dock.
4. Dock reads the sheet (NFC), renders the job for that exact sheet, transfers it, LED solid green, job completed.
5. If nobody comes: after a configurable timeout (default 10 min) the job is cancelled with an error reported to the sender, LED returns to ready. Configurable to "keep queue" mode instead.

## Setup & management

Two layers, both from day one in the architecture but shipped in order:

1. **Local web UI** (v1): served by the Dock itself (`http://repaper-dock.local`). Network settings, printer name, default sheet size, job log, sheet registry, firmware update via upload, LED brightness, timeout.
2. **RePaper Cloud** (v2): the Dock keeps an outbound-only connection (MQTT or WebSocket over TLS). Cloud provides fleet overview, remote config, OTA updates, job history, sheet inventory, alerts ("Dock offline", "Sheet battery low"). Enterprise-friendly: no inbound ports, proxy support, optional fully on-prem mode.

## Enterprise features (later)

- Multiple printer queues on one Dock (e.g. one per sheet size, so the WMS can choose the size by choosing the printer).
- "Named sheet" mode: the WMS prints to *RePaper Dock – Cart 12* and the Dock updates that specific sheet via radio without anyone holding it. (This is where it becomes an ESL system through the back door — but still 100% via printing.)
- PoE, DIN-rail mount, IP54 variant.
- SNMP / printer MIB emulation so print-monitoring tools see it as a normal printer (they check supplies — we report "toner: ∞").
- Audit log, role-based access on the cloud.

## Bill of materials target (production, rough)

Compute module + NFC + radio + LED + enclosure + PSU: aim for a hardware cost that allows a sub-€200 device price in small volume. To be refined once the radio/sheet decision is made.
