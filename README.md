# RePaper

**Reusable paper. Printed the way you already print.**

RePaper builds devices that show up on any network as an ordinary printer (AirPrint / IPP Everywhere / Mopria) — but instead of putting ink on paper, they "print" onto reusable e-paper displays. Nothing to integrate, no SDK, no new workflow: if a system can print, it can print to RePaper.

## Repository layout

```
docs/
  01-vision.md            Company, idea, why now, use cases, moat
  02-brand.md             Name, tagline, positioning, product naming (strategy only)
  03-product-go.md        Product 1: the app that turns a phone/laptop into a RePaper printer
  04-product-dock.md      Product 2: the standalone device (Raspberry Pi prototype first)
  05-architecture.md      Shared tech core: printer emulation, rendering, display transports
  06-epaper-hardware.md   E-paper display options (SoluM, open firmware, NFC-powered) — parked
  07-roadmap.md           Phases, prototypes, open questions to decide together
  08-ecosystem.md         Library / framework check per building block → stack decision
  09-dock-hardware.md     Prototype BOM, the NFC and radio decisions, physical layout
  10-cloud-platform.md    Devices, licensing, fleet view, billing, website — principles and build order
  11-shopping-list.md     Prototype parts with sellers and prices; the Android test phone
brand/
  guidelines.html         Visual brand guide — open in a browser
  tokens.css / .json      Design tokens for every UI (web + native + firmware LED table)
  logo/                   The logo: build script, SVG masters, every exported icon/social/print file
```

Software for each product will live alongside this later (e.g. `core/`, `go/`, `dock/`, `cloud/`).

## Status

Concept stage (August 2026). Documents are drafts meant for discussion — nothing here is decided until it says so.
