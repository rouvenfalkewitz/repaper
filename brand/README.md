# RePaper brand system — v1.0

Source of truth for how RePaper looks, sounds and behaves: the Go app, the Dock's web UI, the cloud console, the website, merch, booths and the device itself.

| File | What it is |
|---|---|
| `guidelines.html` | The visual brand guide — open in a browser (dark-first; switch your OS theme to see Paper mode). |
| `tokens.css` | Design tokens as CSS custom properties, Carbon (dark) default + Paper (light). Import in every web UI. |
| `tokens.json` | Same tokens for native apps and firmware (LED colours + state table). |
| `logo-prompt.md` | Logo brief, prompts for AI image tools, evaluation checklist. |
| `logo/` | (later) final SVG/PNG logo files. |

## The system in ten lines

1. **Name:** RePaper — one word, capital R and P in prose; the wordmark is caps. `repaper` in code.
2. **Feel:** modern hardware start-up. One luminous colour on carbon, confident type, soft shapes, no noise.
3. **Ground:** Carbon `#0C100F` is home (surfaces `#151A18` / `#1C2320`, borders `#273029`, text Paper `#EFF1EE`). Paper mode (`#EFF1EE` / `#FAFBF9` / Ink `#131614`) for docs, print, and as an app option.
4. **Accent:** Re Green. `#1EE3A5` "Glow" on dark (fills, text, LED, merch); `#14C48E` fills and `#0B7259` text on light. Nothing else is ever a brand colour — no orange, no purple.
5. **Primary button = Re Green fill + Carbon/Ink text.** On carbon it's the only element that glows.
6. **Functional:** Label Red, Amber, Signal Blue — dark/light variants in the tokens; they only ever mean their state.
7. **Type:** Archivo (wordmark at width 125 caps, headings at width 100), Figtree body/UI, JetBrains Mono for IDs. 4 px grid, base 16 px.
8. **Shape:** radius 4 (sheets) / 8 (controls) / 16 (cards) / 28 (device) / pill (tags). Hairline borders; depth from light, not from grey.
9. **Signatures:** the e-ink refresh flash (stepped) and the light-ring breathe (3 s). 1-bit dither as the only texture.
10. **Voice:** "It's just a printer." Plain sentences; caps two-word states. Say *sheet, print, hold a sheet to…, printed*. Never tag, sync, gateway, firmware, smart, eco.

## Off-screen

Merch and booths: black + Re Green + white type, nothing else. Real light wherever possible (LED strips, edge-lit logo, the Dock's ring). No white merch, no printed gradients, no eco imagery.

## Working with the tokens

```html
<link rel="stylesheet" href="/brand/tokens.css">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wdth,wght@62..125,600;62..125,700;62..125,800&family=Figtree:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap">
```
Style through the aliases (`--bg`, `--surface`, `--text`, `--accent`, `--accent-text`, `--on-accent`, `--border`, `--glow`) — that is what makes Paper mode free. Native/firmware: `tokens.json` → `theme.carbon` / `theme.paper`, `color.led`, `led`.

## Changing the system

Edit `tokens.css` **and** `tokens.json` together, mirror the `:root` block in `guidelines.html`, bump the version in all three.
