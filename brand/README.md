# RePaper brand system

Source of truth for how RePaper looks, sounds and behaves across every surface: the Go app, the Dock's web UI, the cloud console, the website, packaging and the device itself.

| File | What it is |
|---|---|
| `guidelines.html` | The visual brand guide — open it in a browser. Identity, colour, type, layout, components, sheet & LED language, motion, voice, tokens. |
| `tokens.css` | Design tokens as CSS custom properties (light + dark). Import this in every web UI. |
| `tokens.json` | The same tokens in a W3C-style JSON format for native apps (Android, Tauri, Pi firmware LED table). |
| `logo-prompt.md` | Logo brief + prompts for AI image tools + evaluation checklist. |
| `logo/` | (later) final SVG/PNG logo files. |

## The system in ten lines

1. **Name:** RePaper — one word, capital R and P. `repaper` in code.
2. **Feel:** like e-paper — matte, high-contrast, flat, plain and confident, one playful note.
3. **Ground:** Paper `#EFF1EE`, surfaces Sheet `#FAFBF9`, text Ink `#131614`. Neutrals lean slightly green.
4. **Accent:** Re Green `#14A37F` for fills, icons, LED and the "Re" in the wordmark; `#0B7259` for links/text on light, `#2FCB9F` for text on dark.
5. **Primary button = Re Green fill + Ink text.** Never white on Re Green for body-size text.
6. **Functional colours:** Label Red `#D6402F`, Amber `#E0A21B`, Signal Blue `#2A62D6`. They only ever mean their state.
7. **Type:** Bricolage Grotesque (display/wordmark), Figtree (UI/body, also on-sheet at weight ≥ 500), IBM Plex Mono (IDs, sizes, code). Base 16 px, 4 px grid.
8. **Shape:** radius 4 (sheets) / 6 (controls) / 12 (cards). Hairline borders, no resting shadows, no gradients — dither instead.
9. **Signature motion:** the e-ink refresh flash (stepped invert, 1.6 s, once). LED patterns are stepped too.
10. **Voice:** "It's just a printer." Say *sheet*, *print*, *hold a sheet to…*, *printed*. Never tag, sync, gateway, firmware, smart.

## Working with the tokens

Web (any framework):
```html
<link rel="stylesheet" href="/brand/tokens.css">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,600;12..96,700&family=Figtree:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
```
Then style through the semantic aliases (`--bg`, `--surface`, `--text`, `--accent`, `--accent-text`, `--on-accent`, `--border`), never through raw hex — that is what makes dark mode free.

Native / firmware: read `tokens.json`. The `color.led` block holds the RGB triplets and `led` the state → pattern table the Dock firmware and the app's status pills must both follow.

## Changing the system

Edit `tokens.css` **and** `tokens.json` together, then update `guidelines.html` (its `<style>` `:root` block mirrors the tokens) and bump the version in all three. Fonts are Google Fonts; if we ever self-host, keep the same families.
