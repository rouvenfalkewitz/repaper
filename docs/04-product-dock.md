# 04 · Product 2 — RePaper Dock (the device)

## Summary

A small always-on box that lives on the customer's network as a printer. Print to it from anything; an LED tells you a job is waiting; hold a sheet to the Dock; the sheet updates; the LED confirms; the Dock is ready again.

**Principle: the connection between printer and sheet is physical.** A sheet is printed by the Dock it is held to — like paper coming out of the tray in front of you. The Dock never updates a sheet that isn't right there. This keeps the mental model identical to a printer, needs no gateway infrastructure, and makes "which sheet got which page" unambiguous.

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
- PoE, DIN-rail mount, IP54 variant.
- SNMP / printer MIB emulation so print-monitoring tools see it as a normal printer (they check supplies — we report "toner: ∞").
- Audit log, role-based access on the cloud.

## Bill of materials target (production, rough)

Compute module + NFC + radio + LED + enclosure + PSU: aim for a hardware cost that allows a sub-€200 device price in small volume. To be refined once the radio/sheet decision is made.

## Open question: what should the print dialog offer? (noted 8 Sep 2026)

Field observation from the Go app: Android's print dialog naturally offers color
mode, paper size (our custom "Label 2.9″" media), orientation and so on — because
`PrintService` lets us declare capabilities per printer. The Dock's AirPrint/IPP
side doesn't model any of this yet: `ippeveprinter` advertises one generic set
(`-f image/urf,image/pwg-raster,…`), so a Mac/iPhone shows a plain dialog and the
Dock's renderer makes all the real decisions (auto-rotate, contain, dither).

To decide at some point — with the counter-argument built in: **the Dock's whole
point is speaking regular printer language with zero integration**, and the
current "print anything, the Dock figures it out" behavior may be exactly right.
If we ever do more:
- per-sheet media sizes in the IPP attributes (so page setup matches the sheet
  someone intends to tap) — probably means moving from ippeveprinter to PAPPL,
  where media, color modes and job attributes are properly declarable;
- honor requested orientation instead of always auto-rotating;
- color mode: declare `color` vs `monochrome` based on the fleet's palettes.
Also keep Go and Dock consistent with each other once decided.

## Idea: Dock Light (noted 10 Sep 2026 — not building yet)

A third family member between Dock and Go, captured verbatim from a good shower
thought:

**What it is.** A Dock Light exposes a network printer exactly like the regular
Dock — same AirPrint/IPP face, same Wi-Fi onboarding, same settings UX — but it
knows nothing about sheets. No BLE, no NFC, no sheet registry, no renderer for
panels. That makes the hardware markedly simpler and cheaper (radio + CPU
requirements drop; no OpenDisplay stack on the device).

**How it prints.** A Dock Light REQUIRES the cloud: every job it accepts is
forwarded to RePaper Cloud. In the fleet console you "mirror" the Dock Light to
one or more RePaper Go devices — maybe even to regular Docks. When someone
prints on the Dock Light, all mirrored devices get a push; whichever is first
to actually start the job does the printing (its sheets, its BLE, its choice
logic — auto/cycle/tap as usual).

**v1 simplification.** Allow exactly ONE mirror target per Dock Light to start
with — no race, no claiming protocol, trivially predictable for the user.

**Why it's attractive.**
- Cheapest possible "make this room printable" box; the phone in someone's
  pocket becomes the actual print head.
- An office can put Dock Lights everywhere and keep the few real
  Docks/phones-with-sheets roaming.
- Clean upsell path: Dock Light (cloud-dependent) → Dock (standalone).

**Design consequences to think through before building.**
- Today's privacy stance is "the cloud sees metadata, never pages"
  (docs/05-architecture). Dock Light necessarily ships page content through the
  cloud — that needs an explicit carve-out in the story (e.g. jobs are
  end-to-end encrypted to the mirror target, cloud stores nothing at rest, or
  simply an honest "Dock Light jobs transit the cloud" label).
- Push-to-print on Go means background wake on phones: iOS needs real push
  (APNs) or the job waits until the app opens — the "first one to start wins"
  race only matters once multiple mirrors exist.
- The mirror concept wants a home in the fleet console UI (device page of the
  Dock Light: "Mirrors to: …").

### Refinement to sit on (noted 11 Sep 2026)

Rouven's instinct: Dock and Dock Light are ONE software; the Light is simply
always in mirroring mode, and a regular Dock can be "downgraded" into that mode
by a setting. This is already architecturally true — a Dock Light is `dockd` with
`dock_light: true` (that's how the pilot Pi was switched). The only missing piece
is UX: expose the flag as a Dock setting ("run as a mirror — don't use my own
sheets, send jobs to another device"), so any Dock can act as a Light without a
reflash. Ties into the broader "mirroring mode" UX still to be defined (where it's
controlled, what the mirrored device shows, one-to-one vs one-to-many). Not
building yet — parked for a later refinement pass.
