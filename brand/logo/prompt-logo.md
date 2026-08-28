# Stage 1 — generating the logo mark with ChatGPT

**What you're making:** the logo *mark* only (the icon). No letters, no words, in any image. The wordmark "REPAPER" is typeset later from the real Archivo font.

**What "done" looks like:** 1–3 PNGs of a mark you like, mint-teal green on near-black, saved in `brand/logo/candidates/`. Everything after that (vectorising, recolouring, icons) happens in the repo.

**Exact colours used in every prompt:** background near-black `#0C100F` (RGB 12, 16, 15) · mark mint-teal green `#1EE3A5` (RGB 30, 227, 165). Nothing else.

---

## Before you start

1. Use ChatGPT with image generation. Start a **new chat**.
2. Every image: **square**, **as large as possible**. If there's a size/quality option, take the largest.
3. **Download at full resolution** (download button, not a screenshot). Name files `a-01.png`, `a-02.png`, … and put them in `brand/logo/candidates/`.
4. Judge the **shape** only. Ignore any letters it sneaks in and small wobbles. If it adds text, extra colours or decoration: "Remove the text and extra colours, keep the mark exactly as it is" and regenerate.
5. Prompts are single blocks with no blank lines — paste them exactly as they are.

---

## Step 1 — the primary direction (paste as your first message)

```
TASK: Design a logo mark (icon only) for the brand RePaper. Output one square image, as large as possible (2048×2048 pixels if you can), showing 4 different options of the mark in a 2×2 grid, each option centred on its own square tile.
BRAND: RePaper is a hardware start-up. Its devices show up on any network as a normal printer, but instead of printing on paper they print onto reusable e-paper displays. The brand feel is a modern hardware start-up: flat, minimal, precise, confident, technical but friendly. Think of the flat icon language of Apple SF Symbols or Google Material Symbols at their heaviest weight.
COLOURS (exact, only these two in the entire image): background near-black hex #0C100F (RGB 12, 16, 15) on every tile and in the gutters between tiles; the mark itself is one flat mint-teal green hex #1EE3A5 (RGB 30, 227, 165). No other colours, no tints, no shades, no white, no grey, no anti-aliasing halos in other colours.
THE MARK — WHAT IT IS: a sheet of paper whose folded corner turns into a return arrow. Meaning: "the page comes back — reusable paper".
THE MARK — GEOMETRY: a portrait rectangle (about 4 wide to 5 high) with softly rounded corners (corner radius about 12% of the width). The top-right corner is folded down towards the centre as a right-angled triangle whose legs are about 35% of the sheet's width. The edge of that fold continues into a short, thick, blocky arrow that curves back and points into the sheet (towards the sheet's centre-left). The arrow's stroke is as thick as the sheet's outline (about 14% of the sheet's width) and its arrowhead is a simple solid triangle. The mark is one or two solid shapes at most. All strokes have exactly the same thickness. No thin lines anywhere.
THE MARK — MUST: read clearly at 16 pixels; work as a solid silhouette with no inner detail; be cuttable as a stencil (no floating islands, no thin bridges); sit visually at the weight of extra-bold, extra-wide capital letters.
THE 4 OPTIONS (one per tile, all following the geometry above): Option 1: the sheet is a solid filled shape and the arrow is cut out of the folded corner as negative space (background colour showing through). Option 2: the sheet is a thick outline of even stroke and the folded corner is solid; the arrow grows out of the fold as a solid shape. Option 3: fold and arrow are one single continuous thick stroke, the sheet is a thick outline. Option 4: the whole mark is built from square pixels on a coarse grid (about 12×15 pixels), like a 1-bit e-ink icon, still following the same shape.
LAYOUT: 2×2 grid; each tile is a square with the mark centred and filling about 55% of the tile's width; equal generous margins; no borders, no frames, no dividing lines other than the tiles' own background; no labels, no numbers, no captions.
STYLE — STRICTLY: flat vector illustration. No glow, no gradients, no shadows, no 3D, no bevels, no gloss, no texture, no grain, no paper texture, no sketch or hand-drawn style, no outlines around shapes, no perspective.
DO NOT INCLUDE: any letters, words, numbers or text; any tagline; any background shapes, circles, badges or shields around the mark; mockups, devices, screens, photos, hands; leaves, plants, earth, recycling triangles or recycling arrows; printers, ink drops, clouds, gears, wifi symbols.
```

If the results are weak, don't rewrite the brief — paste one of these:

```
Again, exactly the same brief and the same two colours (#0C100F background, #1EE3A5 mark). Simpler: each mark is one shape, strokes thicker, folded corner bigger (about 40% of the width), arrow clearly readable as an arrow with a solid triangular head.
```

```
Again, exactly the same brief and the same two colours (#0C100F background, #1EE3A5 mark). Make all four marks chunkier, like a bold app-icon glyph: rounded corners everywhere, no thin lines at all, no detail smaller than 10% of the mark's width.
```

Once one tile looks right, isolate it (replace the option number):

```
Show option 2 on its own: exactly the same mark, one square image as large as possible, the mark centred and filling about 60% of the width, flat mint-teal green #1EE3A5 (RGB 30, 227, 165) on solid near-black #0C100F (RGB 12, 16, 15), no other colours, no text, no grid, no frame, nothing else.
```

## Step 2 — explore the mark (paste one at a time, same chat)

Run these on the isolated mark. Each changes exactly one thing; colours never change. Save any result you like.

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: make the fold and the arrow one continuous shape.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: the folded corner becomes a solid triangle and the rest of the sheet a thick outline of equal stroke weight.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: cut the arrow out of the folded corner as negative space so the background shows through.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: rebuild it from square pixels on a coarse grid of about 12×15 pixels, like a 1-bit e-ink icon.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: make the arrow larger and the sheet smaller so the "return" idea is the first thing you see.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: rounder — larger corner radii on the sheet and a softer arrow, still thick and flat.
```

## Step 3 — the alternative direction (optional, same chat)

```
NEW DIRECTION, same rules. TASK: one square image, as large as possible, showing one logo mark centred and filling about 60% of the width. COLOURS (exact, only these two): background near-black #0C100F (RGB 12, 16, 15); mark flat mint-teal green #1EE3A5 (RGB 30, 227, 165). THE MARK: a bold, extra-wide geometric capital letter R whose right leg curves back around and re-enters the letter, forming one continuous loop like a return arrow with a solid triangular arrowhead where it re-enters. The inner counter (hole) of the R is a small portrait rectangle, like a page. Stroke thickness identical everywhere, about 22% of the letter's height; no thin lines. Must read at 16 pixels and work as a solid silhouette. STYLE: flat vector, no glow, no gradients, no shadows, no 3D, no texture, no outlines. DO NOT INCLUDE: any other letters, words, numbers or text; background shapes; mockups; leaves; recycling symbols; printers.
```

Name these `b-01.png`, `b-02.png`, …

## Step 4 — a cheap overview (optional)

```
TASK: a designer's exploration sheet — one square image, as large as possible, showing a 3×3 grid of nine different logo mark concepts for RePaper, a brand about reusable e-paper. COLOURS (exact, only these two): every cell has a near-black #0C100F (RGB 12, 16, 15) background; every mark is flat mint-teal green #1EE3A5 (RGB 30, 227, 165). CONCEPTS: every one combines a sheet of paper with a folded corner and the idea of "again / return / reuse" — through an arrow, a loop, or a repeated shape — each in a clearly different way. STYLE: thick even strokes, geometric, minimal, flat; no gradients, no glow, no shadows, no 3D. LAYOUT: each mark centred in its own cell, filling about 55% of the cell, no borders, no labels, no numbers, no text of any kind. DO NOT INCLUDE: leaves, plants, recycling triangles, printers, clouds, letters.
```

Pull one out: "Show cell 5 (centre) on its own, same rules, one mark filling about 60% of a square image, #1EE3A5 on #0C100F, nothing else."

## Step 5 — finalise the one you like

The cleanest version of the winner — this is the file that gets traced:

```
Take the last mark and render the final clean version: one square image, as large as possible; one flat mint-teal green #1EE3A5 (RGB 30, 227, 165) shape, perfectly centred, filling about 60% of the width, on a solid near-black #0C100F (RGB 12, 16, 15) background; exactly two colours in the image; all strokes exactly the same thickness; all corner radii consistent; symmetrical where it should be; crisp edges; no stray marks, no anti-aliasing halos, no noise, no text, nothing else.
```

Then the same mark in the two other brand contexts, to check it holds (save these too):

```
Same mark, same size and position, one change: the mark is off-white #EFF1EE (RGB 239, 241, 238) on near-black #0C100F (RGB 12, 16, 15). Two colours only.
```

```
Same mark, same size and position, one change: the mark is solid black #000000 on pure white #FFFFFF — this is how it will look printed 1-bit on e-paper. Two colours only.
```

---

## Scoring (0–2 each; keep the top two)

| # | Test | How |
|---|---|---|
| 1 | Reads as a page in < 1 s | Show it to someone for one second: "what is it?" |
| 2 | Reads as "again / back / reuse" | "What does it do?" |
| 3 | 16 px | Zoom out until it's favicon-sized — still distinct from a generic document icon? |
| 4 | Silhouette | Imagine it filled solid with no inner detail — still recognisable? (emboss, diffuser) |
| 5 | Stencil-safe | No floating islands, no thin bridges |
| 6 | Heavy enough | Stroke weight looks like it belongs next to extra-bold wide capitals |
| 7 | Not a cliché | Not a leaf, recycling triangle, printer, cloud, or plain "document" icon |
| 8 | Ownable | Reverse-image search — nothing close from a known brand |

Ignore completely: letters, canvas proportions, tiny wobbles in the strokes, slight colour drift. All of that is fixed in stage 2.

## Hand-off

Save the finalists in `brand/logo/candidates/` and say which file is the pick (and what you like / dislike about it). Stage 2 traces it, cleans it up, sets the wordmark from Archivo, builds the lockups; stage 3 exports every icon and format (see `README.md` and `prompt-assets.md`).
