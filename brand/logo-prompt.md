# RePaper — logo brief & AI prompts

Everything the logo must be is fixed by the brand system (`brand/guidelines.html`). Use this file to generate candidates with an AI image tool, then evaluate them with the checklist at the bottom. Generate the **mark first, monochrome, on white**. Colour and the wordmark come later in a vector editor — never ask the model for the final lockup.

## The idea in one sentence

A page that comes back: a sheet of paper whose folded corner turns into a return arrow (↻).

## Fixed constraints

- Flat vector, single colour (black on white). No gradients, shadows, 3D, gloss, texture, outlines-only, or sketch styles.
- Geometric, few strokes, thick and even stroke weight. Must survive at 16 px, in pure 1-bit black on e-paper, embossed in plastic, and as the silhouette behind a frosted LED diffuser.
- Reads instantly as "paper / a page". Secondary read: "reuse / return / again".
- Square-ish footprint so it works as an app icon and a favicon.
- Friendly, confident, slightly playful — not corporate, not "eco leaf", not "recycling triangle".
- Pairs with the wordmark: **RePaper** in Bricolage Grotesque Bold, "Re" in green `#14A37F`, "Paper" in near-black `#131614`.

## Three directions to generate

**A · Folded corner arrow (primary)** — A rounded-rectangle sheet with its top-right corner folded down; the fold's edge continues into a small curved arrow pointing back into the page.

**B · The looping R** — A bold geometric capital R whose leg curves back and re-enters the bowl, forming a loop; the counter of the R is a small page/rectangle.

**C · Dot-dither page** — A sheet silhouette made of a coarse grid of dots (like 1-bit dithering / e-paper pixels), denser at the bottom, with one folded corner in solid black.

## Prompts

### Midjourney / general image models (direction A)

```
minimalist flat vector logo mark, a single sheet of paper as a rounded rectangle with its top-right corner folded over, the fold's edge flowing into a small curved arrow that points back into the page, meaning "the page comes back / reusable", solid black on pure white background, thick even geometric strokes, no text, no gradient, no shadow, no 3D, no texture, centered, lots of white space, style of modern tech brand identity, iconic, simple enough to read at 16 pixels --style raw --v 6 --no text, letters, gradient, shadow, 3d, realistic, photo, leaves, recycling symbol, mockup
```

Variations to run:
- `... the arrow is part of the fold, one continuous shape ...`
- `... two-tone: the folded corner filled solid, the rest of the sheet an outline of equal stroke ...`
- `... negative space version: white arrow cut out of a solid black sheet ...`

### Direction B

```
minimalist flat vector logo mark, bold geometric capital letter R whose right leg curves back around and re-enters the letter forming a continuous loop like a return arrow, the inner counter of the R is a tiny rectangle like a page, solid black on pure white, thick even stroke, monoline, no text besides the single letter, no gradient, no shadow, no 3D, centered, large white margin, modern tech brand identity --style raw --v 6 --no gradient, shadow, 3d, realistic, photo, leaves, recycling symbol, mockup, words
```

### Direction C

```
minimalist flat vector logo mark, silhouette of a sheet of paper with one folded corner, the sheet is drawn as a coarse grid of black dots like a 1-bit dithered e-ink pixel pattern, dots dense at the bottom and sparse at the top, the folded corner is a solid black triangle, pure white background, no text, no gradient, no shadow, no 3D, centered, large white margin, modern tech brand identity, reads as an icon at small size --style raw --v 6 --no gradient, shadow, 3d, realistic, photo, leaves, recycling symbol, mockup, words
```

### Text-capable models (Ideogram, GPT image, Imagen, Flux) — mark + wordmark exploration

Only for exploring lockups; the shipping wordmark is typeset, not generated.

```
Logo design for "RePaper", a company making reusable e-paper you print on like a normal printer. Layout: a small icon to the left of the word "RePaper" in a bold geometric sans-serif (similar to Bricolage Grotesque or Sora). The icon is a sheet of paper with a folded corner that turns into a small return arrow. The letters "Re" are green (#14A37F), the letters "Paper" are near-black (#131614). Flat vector, white background, no gradients, no shadows, no 3D, no tagline, no other text. Clean, confident, slightly playful tech brand.
```

### App icon / LED diffuser exploration

```
app icon, rounded square, solid green background (#14A37F), a single black minimalist flat vector symbol centered: a sheet of paper with a folded corner that turns into a small return arrow, no text, no gradient, no shadow, flat design, large margins
```

## Evaluation checklist (score each candidate 0–2)

| # | Test | How |
|---|---|---|
| 1 | Reads as a page in < 1 s | Show to someone for a second, ask "what is it?" |
| 2 | Reads as "again / back / reuse" as second meaning | Ask "what does it do?" |
| 3 | 16 px | Scale down; is the shape still distinct from a generic document icon? |
| 4 | 1-bit | Threshold to pure black/white; no grey needed, no thin bits vanish |
| 5 | Emboss / diffuser | Fill solid; silhouette alone still works (no inner detail required) |
| 6 | Not a cliché | Not a leaf, not the recycling triangle, not a printer, not a cloud |
| 7 | Sits next to the wordmark | Cap-height of the mark = cap-height of the "R"; visually equal weight to the letters |
| 8 | Ownable | Search similar-image; nothing close from a major brand |

Keep the top 2–3, redraw them cleanly as SVG (the AI output is a reference, not the final file), then test again at 16 px and 1-bit.

## Deliverables once chosen

`brand/logo/` with: `mark.svg`, `mark-mono-black.svg`, `mark-mono-white.svg`, `lockup-horizontal.svg` (mark + wordmark), `lockup-mono.svg`, `app-icon-1024.png`, `favicon.svg`, plus a `1bit/` folder with the mark rasterised at 32/64/128 px pure black-and-white for on-sheet use.
