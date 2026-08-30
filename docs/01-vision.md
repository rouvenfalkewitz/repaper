# 01 · Vision

## One-liner

RePaper replaces single-use paper with reusable e-paper — without asking anyone to change how they print.

## The insight

E-paper / electronic shelf labels are a mature, cheap, ultra-low-power display technology. Yet outside of retail shelves they are barely used, because every deployment needs *integration*: an API, a gateway, a vendor SDK, someone writing glue code against the ERP / WMS / LIMS / booking system.

Printers, on the other hand, are integrated **everywhere**. Every OS, every ERP, every label tool, every hospital and warehouse system can print. Printing is the universal, already-approved, already-firewalled output channel of the enterprise.

RePaper's move: **be the printer.** Our devices advertise themselves as a standard network printer. Any existing system prints to them exactly as it would to a Zebra or a HP — and the output appears on a reusable e-paper display instead of on paper.

- Zero integration: plug-and-play replacement of an existing printer / print queue.
- Physical: a sheet is printed by the printer it is held to. No gateways, no "which display is this" configuration — the same mental model as paper coming out of a tray.
- Reusable: one display replaces thousands of sheets or labels over its life.
- Smart: the "paper" can be updated, tracked, and reused — without becoming an IoT project for the customer.

## Products (initial)

| | Product | What it is |
|---|---|---|
| 1 | **RePaper Go** (working name) | An app that runs on a phone / tablet / laptop and makes that device appear as a printer on the network. Incoming print job → notification → hold the device to an e-paper sheet → it "prints" onto that sheet. |
| 2 | **RePaper Dock** (working name) | A small standalone box (Raspberry Pi prototype first) that does the same permanently: sits on the network as a printer, LED signals an incoming job, hold a sheet next to it, done. Set up via local web UI and/or cloud. |
| — | **RePaper Sheets** | The e-paper displays themselves. Sourced from partners (SoluM relationship; open firmware from a friend). See `06-epaper-hardware.md`. |
| — | **Accessories** | Mounts for warehouse carts, racks, doors, desks; lanyards; magnetic backs; charging/handling trays. |

Both products share one software core (`05-architecture.md`): printer emulation → rendering → transfer to a display.

## Use cases

**Industry / logistics**
- Warehouse cart & pallet labels, pick lists, kanban cards — printed from the WMS as today, but onto a display fixed to the cart.
- Work orders on machines / workstations: the MES prints the next job to the display at the station.
- Inbound/outbound dock signage, staging-area labels, container/tote labels.
- Sample/tray labels in labs (LIMS already prints labels).

**Office / hospitality / healthcare**
- Meeting-room and desk signage printed from the booking tool (it already can "print" a confirmation).
- Patient bed / door cards printed from the HIS instead of paper cards.
- Hotel/event signage, name badges, table cards.
- Personal "print to my sheet": to-do lists, tickets, recipes, boarding passes — printed to a sheet you carry.

**Retail** (later — SoluM's home turf, tread carefully)
- Backroom & promo signage where the store already has a printer workflow but no ESL integration.

## Why now

- E-paper prices and sizes (2.9" up to 13.3"+; BW, BWR, BWRY, colour) have hit the point where "a display per cart/door/bed" is affordable.
- IPP Everywhere / AirPrint / Mopria mean driverless printing is standard in every OS and most enterprise systems — emulating a printer is now a well-defined, stable target.
- Sustainability reporting (CSRD etc.) makes "we eliminated N thousand paper labels per year" a concrete, reportable win for customers.
- Open ESL firmware exists, so we can prototype without waiting for a manufacturer SDK.

## What we are *not*

- Not an ESL system for retail pricing (SoluM & co. already own that; we partner, we don't compete).
- Not an IoT platform. We deliberately hide all of that behind "it's a printer".
- Not a display manufacturer. We buy displays; our value is the printer illusion, the UX, and the accessories/ecosystem around it.

## Moat / defensibility

1. **UX of the illusion** — the details of behaving like a great printer (media sizes, job states, multi-page, error handling) so that *any* system works first try. This is engineering depth that's easy to underestimate.
2. **Ecosystem** — mounts, holders, sheet formats, fleet management, cloud. The displays are the razor, the ecosystem is the blades.
3. **Partnerships** — manufacturer relationship (SoluM) and firmware know-how give us hardware access others don't have.
4. **Brand** — "RePaper" as the category name for reusable, printable paper.

## Business model (first thoughts)

- Hardware: Dock devices + Sheets + mounts (margin on hardware).
- Software: Go app free/basic, paid for teams; cloud fleet management as subscription per Dock/Sheet.
- Enterprise: pilot projects with a fixed price, then per-site rollout.
