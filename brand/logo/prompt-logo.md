# Stage 1 — generating the logo mark with ChatGPT

**What you're making:** the logo *mark* only (the icon). No letters, no words, in any image. The wordmark "REPAPER" is typeset later from the real Archivo font, so an image model never has to draw text.

**What "done" looks like:** 1–3 black-on-white PNGs of a mark you like, saved in `brand/logo/candidates/`. Everything after that (vectorising, colours, icons) happens in the repo.

---

## Before you start

1. Use ChatGPT with image generation (GPT-4o / GPT-5 with images). Start a **new chat** so nothing else leaks in.
2. Every image: ask for **square** and **as large as possible**. If the tool offers a size or "HD" option, take the largest.
3. **Download at full resolution** (the download button, not a screenshot). Name files `a-01.png`, `a-02.png`, … (direction letter + number) and put them in `brand/logo/candidates/`.
4. Judge the **shape** only. Ignore any letters it sneaks in, ignore colour, ignore exact proportions of the canvas. If ChatGPT adds text or a background, say "remove the text / background, keep the mark" and regenerate.

---

## Step 1 — the primary direction (paste this as your first message)

```
Create a logo mark (icon only, no letters, no words, no text of any kind) for a brand called RePaper.

About the brand: RePaper makes reusable e-paper you print on like a normal printer. The style is a modern hardware start-up: flat, minimal, confident.

What to draw: a sheet of paper drawn as a rounded rectangle whose top-right corner is folded down; the edge of the fold continues into a small, blocky arrow that points back into the page — meaning "the page comes back / reusable". Thick, even, geometric strokes. Simple enough to read at 16 pixels and to work as a solid silhouette or a stencil (no floating islands, no thin bridges).

Rendering: solid black on a pure white background, the mark centered and filling about 60% of a square canvas, generous empty margin. Make the image square and as large as you can (2048×2048 if possible).

Strictly flat vector style: no glow, no gradients, no shadows, no 3D, no gloss, no texture, no outlines-only, no sketch style. No background shapes, no mockups, no photos, no letters, no words, no tagline, no leaves, no recycling triangles, no printers, no clouds.
```

If the first result is off, don't rewrite the brief — paste:

```
Regenerate with the same brief. Keep it simpler: fewer shapes, thicker strokes, one clear fold, one clear arrow.
```

## Step 2 — explore the mark (paste one at a time, in the same chat)

Run each of these after Step 1. Each one changes exactly one thing. Save any result you like.

```
Same brief, but make the fold and the arrow one continuous shape.
```

```
Same brief, but the folded corner is a solid triangle and the rest of the sheet is an outline of equal stroke weight.
```

```
Same brief, but cut the arrow out of the folded corner as negative space.
```

```
Same brief, but build the mark from square pixels on a coarse grid, like a 1-bit e-ink icon.
```

```
Same brief, but make the arrow larger and the sheet smaller, so the "return" idea is the first thing you see.
```

## Step 3 — the alternative direction (optional, same chat)

A different idea, in case the folded corner doesn't land:

```
New direction, same rules (icon only, black on white, flat, square, as large as possible): a bold, extra-wide geometric capital letter R whose right leg curves back around and re-enters the letter, forming a continuous loop like a return arrow. The inner hole of the R is a small rectangle, like a page. Thick, even strokes; readable at 16 pixels; works as a solid silhouette.
```

Name these `b-01.png`, `b-02.png`, …

## Step 4 — a cheap overview (optional)

If you want to see many ideas at once before committing:

```
A designer's exploration sheet: a 3×3 grid of nine different flat logo mark concepts for a brand about reusable paper. Each mark isolated in its own cell, solid black on white, no text, no labels, no numbers. Every concept combines a sheet of paper with a folded corner and the idea of "again / return / reuse" — through an arrow, a loop, or a repeated shape. Thick even strokes, geometric, minimal. No gradients, no shadows, no 3D, no leaves, no recycling triangles.
```

Point at a cell you like: "Make cell 5 (middle) as a single large mark, same rules as before."

## Step 5 — finalise the one you like

Once a direction is right, get the cleanest possible version — this is the file that gets traced:

```
Take the last mark and render it as a final clean version: solid black on pure white, perfectly centered, filling about 60% of a square canvas, all strokes exactly the same thickness, all corners consistent, symmetrical where it should be, no stray marks, no anti-aliasing artefacts, as large as possible.
```

Optional — only to *see* it in brand colour (do not use this one for tracing):

```
Show the same mark in mint-teal green (#1EE3A5) on a solid near-black background (#0C100F). Nothing else changed: flat, no glow, no gradient, no shadow.
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

Ignore completely: letters, colour, canvas proportions, tiny wobbles in the strokes. All of that is fixed in stage 2.

## Hand-off

Save the finalists in `brand/logo/candidates/` and say which file is the pick (and what you like / dislike about it). Stage 2 then traces it, cleans it up, sets the wordmark from Archivo, builds the lockups, and stage 3 exports every icon and format (see `README.md` and `prompt-assets.md`).
