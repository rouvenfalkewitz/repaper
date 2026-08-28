# 07 · Roadmap & open questions

## Phase 0 — Concept (now)

- [x] Idea, vision, brand direction, product outlines (these docs)
- [ ] Agree on working names, decide on the tech stack for the core
- [ ] Check domain/handle availability, register
- [ ] Talk to the firmware friend: models, speed, licence, willingness to collaborate
- [ ] Order prototype hardware (Pi, PN532, RGB LED, ESP32, 3–5 tags of 2 sizes, 1–2 NFC-powered panels)

## Phase 1 — "It shows up as a printer" (tech spike, ~2–3 weeks)

- Core: mDNS + IPP server that macOS, iOS, Windows and Android all discover and print to; jobs land as PDF/URF/PWG on disk.
- Renderer: PDF + URF + PWG → dithered bitmap for a 4.2" BWR panel; PNG preview.
- Success criterion: print a page from an iPhone, see a correct 400×300 preview PNG.

## Phase 2 — First Dock prototype (~3–4 weeks)

- Pi + PN532 + LED + ESP32 AP; the full loop: print → LED green → tap tag → image appears → LED solid green.
- Local web UI for Wi-Fi + printer name.
- Demo video. This is the artefact for the SoluM conversation and for first customer chats.

## Phase 3 — RePaper Go (Android) (~4–6 weeks)

- Core embedded in an Android app with foreground service; NFC tap; job inbox; sheet registry.
- NFC-powered panel support for the no-infrastructure demo.

## Phase 4 — Pilot readiness

- Cloud console MVP (claim device, see status, push config, OTA).
- 3D-printed enclosure v1, 2 mount types.
- 1–2 pilot sites (a warehouse and an office), measure sheets saved, tap-to-print success rate, time-to-first-print.

## Open questions for us to decide

1. **Names**: keep *Go* / *Dock* / *Sheet* as working names, or something else?
2. **Core stack**: Rust core + thin shells (my recommendation) vs. Go binary + separate Android app vs. CUPS-based quick hack for the Dock prototype (fastest demo, but throwaway).
3. **Tap semantics**: is the tap a *selection* (identify sheet, transfer via radio) or the *transfer itself* (NFC-powered sheet)? Both are supported by the architecture, but the first prototype should pick one. Depends on what the friend's firmware offers over NFC.
4. **Which sheet sizes first?** Suggest 2.9" (label) and 4.2" (card) plus one 7.5" (page) for the demo.
5. **SoluM**: what do we want from them at this stage — sample tags, API docs, or a co-development conversation? Timing: before or after the demo video?
6. **Company**: where to incorporate, who's in (you, firmware friend?), how to structure the SoluM partnership.
7. **Colour**: BWR (black/white/red) makes labels far more useful (highlight, "urgent") — do we require it from day one?
