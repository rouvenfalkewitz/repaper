# Stage 1 — generating the logo mark with ChatGPT

**What you're making:** the logo *mark* only (the icon). No letters, no words, in any image. The wordmark "REPAPER" is typeset later from the real Archivo font, so an image model never has to draw text.

**What "done" looks like:** 1–3 PNGs of a mark you like (green on near-black), saved in `brand/logo/candidates/`. Everything after that (vectorising, recolouring, icons) happens in the repo.

---

## Before you start

1. Use ChatGPT with image generation (GPT-4o / GPT-5 with images). Start a **new chat** so nothing else leaks in.
2. Every image: ask for **square** and **as large as possible**. If the tool offers a size or "HD" option, take the largest.
3. **Download at full resolution** (the download button, not a screenshot). Name files `a-01.png`, `a-02.png`, … (direction letter + number) and put them in `brand/logo/candidates/`.
4. Judge the **shape** only. Ignore any letters it sneaks in and small wobbles in the strokes. If ChatGPT adds text, extra colours or decoration, say "remove the text / extra colours, keep the mark exactly as it is" and regenerate.
5. Colours are always the brand's: mark mint-teal green `#1EE3A5` on near-black `#0C100F`. Every prompt below says so; if a result comes back in other colours, ask for it again in these.

---

## Step 1 — the primary direction (paste this as your first message)

```
Design a logo mark (icon only — no letters, no words, no text anywhere) for RePaper, a hardware start-up that makes reusable e-paper you print on like a normal printer.

Show 4 different options in a 2×2 grid, each option on its own solid near-black (#0C100F) square tile with generous empty margin. The mark in every option is a single flat mint-teal green (#1EE3A5) shape — exactly two colours in the whole image: #0C100F background and #1EE3A5 mark.

The mark: a sheet of paper, drawn like a bold system icon (think of a "document" glyph in Apple SF Symbols or Google Material, at the heaviest weight): a tall rounded rectangle, portrait orientation, with its top-right corner folded down as a triangle. The distinctive part: the edge of the fold continues into a short, thick, blocky arrow that curves back and points into the page — meaning "the page comes back / it's reusable". One or two shapes at most. Thick, even, geometric strokes. It must read at 16 pixels, work as a solid silhouette, and be cuttable as a stencil (no floating islands, no thin bridges).

Vary across the 4 options: (1) sheet as a filled shape with the arrow cut out of the fold in negative space; (2) sheet as a thick outline with a solid folded corner; (3) fold and arrow as one continuous stroke; (4) the whole mark built from square pixels on a coarse grid, like a 1-bit e-ink icon.

Strictly flat vector style: no glow, no gradients, no shadows, no 3D, no gloss, no texture, no sketch style. No background shapes, no mockups, no photos, no letters, no words, no numbers, no labels, no tagline, no leaves, no recycling triangles, no printers, no clouds.

Square image, as large as possible (2048×2048 if you can).
```

If the results are weak, don't rewrite the brief — paste one of these:

```
Again, same brief. Simpler: each mark is one shape, thicker strokes, bigger folded corner, the arrow clearly visible as an arrow.
```

```
Again, same brief, but make the marks look like they belong to the same family as a bold app-icon glyph: chunky, rounded corners, no thin lines at all.
```

Once one tile looks right, isolate it:

```
Show option 2 alone: one mark, centered, filling about 60% of a square canvas, mint-teal green (#1EE3A5) on solid near-black (#0C100F), nothing else, as large as possible.
```

## Step 2 — explore the mark (paste one at a time, in the same chat)

Run these on the isolated mark from Step 1. Each one changes exactly one thing. Same colours throughout (green #1EE3A5 on near-black #0C100F). Save any result you like.

```
Same mark, but make the fold and the arrow one continuous shape.
```

```
Same mark, but the folded corner is a solid triangle and the rest of the sheet is a thick outline of equal stroke weight.
```

```
Same mark, but cut the arrow out of the folded corner as negative space.
```

```
Same mark, but built from square pixels on a coarse grid, like a 1-bit e-ink icon.
```

```
Same mark, but make the arrow larger and the sheet smaller, so the "return" idea is the first thing you see.
```

```
Same mark, but rounder: larger corner radii on the sheet, softer arrow, still thick and flat.
```

## Step 3 — the alternative direction (optional, same chat)

A different idea, in case the folded corner doesn't land:

```
New direction, same rules (icon only, flat, mint-teal green #1EE3A5 on solid near-black #0C100F, square, as large as possible): a bold, extra-wide geometric capital letter R whose right leg curves back around and re-enters the letter, forming a continuous loop like a return arrow. The inner hole of the R is a small rectangle, like a page. Thick, even strokes; readable at 16 pixels; works as a solid silhouette.
```

Name these `b-01.png`, `b-02.png`, …

## Step 4 — a cheap overview (optional)

If you want to see many ideas at once before committing:

```
A designer's exploration sheet: a 3×3 grid of nine different flat logo mark concepts for a brand about reusable paper. Each mark isolated in its own near-black (#0C100F) cell, drawn in flat mint-teal green (#1EE3A5), no text, no labels, no numbers. Every concept combines a sheet of paper with a folded corner and the idea of "again / return / reuse" — through an arrow, a loop, or a repeated shape. Thick even strokes, geometric, minimal. No gradients, no shadows, no 3D, no leaves, no recycling triangles.
```

Point at a cell you like: "Make cell 5 (middle) as a single large mark, same rules as before."

## Step 5 — finalise the one you like

Get the cleanest possible version of the winner — this is the file that gets traced:

```
Take the last mark and render it as a final clean version: one flat mint-teal green (#1EE3A5) shape on a solid near-black (#0C100F) background, perfectly centered, filling about 60% of a square canvas, all strokes exactly the same thickness, all corners consistent, symmetrical where it should be, no stray marks, no anti-aliasing halos, no other colours. As large as possible.
```

Then the same mark once more in the two other brand contexts, just to check it holds (save these too):

```
Same mark, same size and position, but off-white (#EFF1EE) on near-black (#0C100F).
```
```
Same mark, same size and position, but solid black (#000000) on pure white (#FFFFFF) — this is how it will look printed 1-bit on e-paper.
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

Save the finalists in `brand/logo/candidates/` and say which file is the pick (and what you like / dislike about it). Stage 2 then traces it, cleans it up, sets the wordmark from Archivo, builds the lockups, and stage 3 exports every icon and format (see `README.md` and `prompt-assets.md`).
