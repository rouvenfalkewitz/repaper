# Stage 1 — logo generation prompts

Copy-paste into a text-capable image model (GPT Image, Ideogram, Imagen, Flux, Midjourney v7 with text). Ask for the largest square output available (ideally 2048 × 2048). Generate 4–8 per prompt. Save to `brand/logo/candidates/`.

Remember: **we only need the mark and the proportions.** The letters get re-set from the real font later. Don't burn credits chasing perfect typography.

---

## Fixed brief (paste this above any prompt if the tool supports a system/context field)

```
Brand: REPAPER — a company making reusable e-paper you print on like a normal printer.
Style: modern hardware start-up. Flat vector. Dark-first.
Colours: background near-black #0C100F. Accent mint-teal green #1EE3A5. Off-white #EFF1EE.
Never: gradients, glow, shadows, 3D, gloss, texture, outlines-only, sketch style, mockups, photos, extra text, taglines, leaves, recycling triangles, printers, clouds.
The mark must read at 16 px, as pure black on white, as a solid silhouette, and cut as a stencil.
```

---

## Prompt L1 — the lockup (mark + wordmark), primary direction

```
Flat vector logo lockup on a solid near-black background (#0C100F), centered, generous empty margin on all sides.
Left: a logo mark — a sheet of paper drawn as a rounded rectangle whose top-right corner is folded down; the fold's edge continues into a small blocky arrow that points back into the page, meaning "the page comes back / reusable". Thick, even, geometric strokes; the same stroke weight as the letters next to it.
Right: the word "REPAPER" in all capitals, in an extra-bold, extra-wide grotesque sans-serif (like Archivo Expanded or Monument Extended), tight letter-spacing. The letters "RE" are mint-teal green (#1EE3A5); the letters "PAPER" are off-white (#EFF1EE). The mark is the same mint-teal green. Mark height equals the capital letter height.
Strictly flat: no glow, no gradients, no shadows, no 3D, no texture, no background shapes, no tagline, no other text. Clean, confident, modern hardware start-up brand identity.
```

Variations to run on L1 (change one line at a time):
- `... the fold and the arrow are one continuous shape ...`
- `... the folded corner is a solid triangle, the rest of the sheet is an outline of equal stroke ...`
- `... the arrow is cut out of the folded corner in negative space ...`
- `... the mark is built from square pixels on a coarse grid, like a 1-bit e-ink icon ...`

## Prompt L2 — the lockup, alternative direction (looping R)

```
Flat vector logo lockup on a solid near-black background (#0C100F), centered, generous empty margin.
Left: a logo mark — a bold, extra-wide geometric capital letter "R" whose right leg curves back around and re-enters the letter, forming a continuous loop like a return arrow; the counter (inner hole) of the R is a small rectangle like a page. Thick, even strokes.
Right: the word "REPAPER" in all capitals in an extra-bold, extra-wide grotesque sans-serif, tight letter-spacing; "RE" in mint-teal green (#1EE3A5), "PAPER" in off-white (#EFF1EE). The mark is mint-teal green. Mark height equals capital letter height.
Strictly flat: no glow, no gradients, no shadows, no 3D, no texture, no tagline, no other text. Modern hardware start-up brand.
```

## Prompt M — mark only (run on the winning direction)

```
A single flat vector logo mark, centered on a solid near-black background (#0C100F), filling about 60% of the canvas, no text at all.
[paste the mark description from L1 or L2 here]
Mint-teal green (#1EE3A5) on near-black. Thick, even, geometric strokes; simple enough to read at 16 pixels; works as a solid silhouette; no islands or thin bridges (stencil-safe).
Strictly flat: no glow, no gradients, no shadows, no 3D, no texture, no letters, no words.
```

Also run M once with `solid black on pure white background` instead of green on near-black — this is the 1-bit test, and the version you will trace.

## Prompt S — concept sheet (cheap exploration)

```
A designer's exploration sheet: a 3×3 grid of nine different flat vector logo mark concepts for a brand about reusable paper, each mark isolated in its own cell, black on white, no text, no labels. Every concept combines a sheet of paper with a folded corner and the idea of "again / return / reuse" — through an arrow, a loop, or a repeated shape. Thick even strokes, geometric, minimal, modern tech brand. No gradients, no shadows, no 3D, no leaves, no recycling triangles.
```

## Midjourney form of M (if you use MJ)

```
minimalist flat vector logo mark, a sheet of paper as a rounded rectangle with its top-right corner folded down, the fold flowing into a small blocky return arrow pointing back into the page, mint-teal green (#1EE3A5) on a solid near-black (#0C100F) background, thick even geometric strokes, no text, centered, large margin, modern hardware brand identity, readable at 16 pixels --style raw --v 7 --no text, letters, words, gradient, glow, shadow, 3d, realistic, photo, leaves, recycling symbol, mockup
```

---

## Scoring candidates (0–2 each; keep the top two)

| # | Test | How |
|---|---|---|
| 1 | Reads as a page in < 1 s | Show it to someone for one second: "what is it?" |
| 2 | Reads as "again / back / reuse" | "What does it do?" |
| 3 | 16 px | Zoom out until it's a favicon; still distinct from a generic document icon? |
| 4 | 1-bit | Threshold to pure black/white — nothing important vanishes |
| 5 | Silhouette | Fill solid: still recognisable with no inner detail (this is the diffuser / emboss version) |
| 6 | Stencil-safe | No floating islands, no thin bridges |
| 7 | Same material as the letters | Stroke weight ≈ stem weight of the wide Archivo "R"; sits on the same baseline |
| 8 | Not a cliché | Not a leaf, not the recycling triangle, not a printer, not a cloud, not a generic "document" icon |
| 9 | Works as light | Imagined as the blurry silhouette behind the Dock's frosted ring — still reads |
| 10 | Ownable | Reverse-image search; nothing close from a known brand |

Ignore: the spelling and letterforms of "REPAPER", kerning, exact colours. All of that is fixed in stage 2.
