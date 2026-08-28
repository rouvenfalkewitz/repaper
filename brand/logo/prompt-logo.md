# Stage 1 — generating the logo mark with ChatGPT

**What you're making:** the logo *mark* only (the icon). No letters, no words, in any image. The wordmark "REPAPER" is typeset later from the real Archivo font.

**How this brief works:** it tells the model what RePaper is, what the design system looks like, and what the hard constraints are — and then makes the model find the idea. It never prescribes a shape. A prescribed shape ("a sheet with a folded corner and an arrow") gives you the same generic icon every time.

**What "done" looks like:** 1–3 PNGs of a mark you like, mint-teal green on near-black, saved in `brand/logo/candidates/`. Everything after that (vectorising, recolouring, icons) happens in the repo.

**Exact colours used in every prompt:** background near-black `#0C100F` (RGB 12, 16, 15) · mark mint-teal green `#1EE3A5` (RGB 30, 227, 165). Nothing else.

---

## Before you start

1. Use ChatGPT with image generation. Start a **new chat**.
2. Every image: **square**, **as large as possible**. If there's a size/quality option, take the largest.
3. **Download at full resolution** (download button, not a screenshot). Name files `sheet-01.png` for concept sheets and `mark-01.png`, `mark-02.png`, … for isolated marks; put them in `brand/logo/candidates/`.
4. Judge the **shape** only. Ignore any letters it sneaks in and small wobbles. If it adds text, extra colours or decoration: "Remove the text and extra colours, keep the mark exactly as it is" and regenerate.
5. Prompts are single blocks with no blank lines — paste them exactly as they are.

---

## Step 1 — open brief (paste as your first message)

Also saved as plain text in `prompt-logo-step1.txt` — open it, select all, copy.

The brief deliberately does **not** describe a shape. Telling a model "a sheet with a folded corner and an arrow" produces the same clip-art every time. It gets the brand, the design system and the hard constraints, and has to find the idea itself.

```
ROLE: You are a senior brand designer at a studio known for abstract, memorable logo marks (think of the level of Pentagram or Koto). You are designing the logo mark for RePaper.
TASK: Propose 6 distinct logo mark concepts, each based on a different idea, presented in one square image as a 3×2 grid, each concept centred on its own square tile. Icon only — no letters, no words, no text anywhere in the image. Output as large as possible (2048×2048 pixels if you can).
WHAT REPAPER IS: a hardware start-up. Its devices appear on any network as an ordinary printer. But instead of printing on paper, they "print" onto reusable e-paper displays ("sheets"): a print job arrives, a light on the device blinks, you hold a sheet to it, and the page appears on the sheet. A sheet can be reprinted thousands of times. Nothing has to be integrated, because every system in the world already knows how to print. Customers are warehouses, factories, labs, offices and hotels first; consumers later. The promise: "Reusable paper. Printed the way you already print." Themes to draw from: reuse and return; the same surface used again and again; printing without paper; e-ink (1-bit black and white, pixels, the panel's refresh flash); the printer as a familiar, trusted object; plug-and-play simplicity; the "Re" in the name.
DESIGN SYSTEM THE MARK MUST BELONG TO: dark-first, modern hardware brand — matte carbon black, one luminous mint-teal green, off-white type, soft rounded geometry, hairline borders, no noise. Palette: Carbon #0C100F (ground), Paper off-white #EFF1EE (text on dark), Re Green #1EE3A5 (the one accent), Ink #131614 (text on light). The wordmark is "REPAPER" in Archivo Extra-Bold at 125% width, all caps, tight tracking, "RE" in green and "PAPER" in off-white; the mark sits to the left of it at capital-letter height, so its visual weight must match extra-bold, extra-wide capitals. Shape language: rounded rectangles (radius 4–28 px), pills, flat surfaces, depth only from real light. Motifs already in the system: 1-bit ordered dither (Bayer pattern) as the only texture; the e-ink refresh flash as the signature motion. The product is a matte carbon puck with a frosted light ring; the mark will be the illuminated silhouette behind that diffuser, embossed in the plastic, printed flat on black merch, and rendered in 1-bit on the e-paper sheets themselves. References for finish: Apple SF Symbols and Material Symbols at their heaviest weight; ProGlove and Razer for one colour on black — but always flat.
WHAT MAKES A GOOD ANSWER: abstract rather than literal; one idea per mark, executed in one or two shapes; memorable enough to be drawn from memory after seeing it once; ownable — nothing that already exists as a common icon; reads at 16 pixels; works as a solid silhouette; stencil-safe (no floating islands, no thin bridges); strokes all the same thickness, no thin lines; friendly through rounded corners, confident through weight. The mark may be built from geometry, from negative space, from a pixel grid, from a single continuous line, from a repeated element, or from a letterform derived from "R" or "Re" — your choice, but the 6 concepts must use 6 different approaches.
DO NOT DO THE OBVIOUS: no document icon with a folded corner; no sheet with an arrow; no recycling triangle or circular arrows; no printer; no leaves, plants, earth, drops or clouds; no lightbulb; no wifi symbol; no gears; no badge, circle or shield around the mark; no mascots.
COLOURS (exact, only these two in the entire image): background near-black hex #0C100F (RGB 12, 16, 15) on every tile and in the gutters; every mark is one flat mint-teal green hex #1EE3A5 (RGB 30, 227, 165). No other colours, no tints, no white, no grey.
LAYOUT: 3×2 grid of equal square tiles; each mark centred, filling about 50% of its tile; equal generous margins; no borders, no frames, no dividing lines, no labels, no numbers, no captions.
STYLE — STRICTLY: flat vector. No glow, no gradients, no shadows, no 3D, no bevels, no gloss, no texture, no grain, no sketch or hand-drawn style, no outlines around shapes, no perspective, no mockups, no photos, no letters, no words, no text.
```

Then make it explain itself — this is where the good ideas surface:

```
For each of the 6 concepts, in one sentence each: what is the idea, and why would someone remember it? Then tell me which two you would take forward and why.
```

If the sheet is generic, push once (don't rewrite the brief):

```
Again, same brief and the same two colours (#0C100F background, #1EE3A5 marks). These are too literal. Go more abstract: pure geometry, negative space, rhythm, a pixel grid, a single continuous line. Every concept must be something I haven't seen as an icon before.
```

```
Again, same brief and the same two colours. Six new concepts, each derived from a different one of these starting points: (1) the letter R reduced to its simplest geometric gesture; (2) "Re" as one shape; (3) a rectangle that is used twice; (4) a 1-bit pixel pattern that forms a shape; (5) a single continuous thick line; (6) negative space in a solid rounded square.
```

Isolate one (replace the number; count tiles left-to-right, top-to-bottom):

```
Show concept 4 on its own: exactly the same mark, one square image as large as possible, the mark centred and filling about 60% of the width, flat mint-teal green #1EE3A5 (RGB 30, 227, 165) on solid near-black #0C100F (RGB 12, 16, 15), no other colours, no text, no grid, no frame, nothing else.
```

## Step 2 — refine the isolated mark (paste one at a time, same chat)

Each changes exactly one thing; colours never change. Save any result you like.

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: simplify — remove everything that is not essential to the idea.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: make it heavier — thicker strokes, so it matches extra-bold, extra-wide capital letters.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: rounder corners everywhere, still flat and geometric.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: rebuild it as a solid shape with the idea carried by negative space.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: rebuild it from square pixels on a coarse grid, like a 1-bit e-ink icon.
```

```
Same mark, same two colours (#0C100F background, #1EE3A5 mark), same layout, one change: make it more asymmetric and dynamic while keeping it stencil-safe.
```

## Step 3 — seed a direction (optional, same chat)

Only if you want to steer. One seed per message, everything else unchanged:

```
Six new concepts, same brief and same two colours, all built around the letter R: R as a single continuous thick line; R reduced to two rectangles; R where the leg returns into the bowl; R cut out of a rounded square as negative space; R on a 1-bit pixel grid; R that reads as an "e" upside down. No other letters, no words.
```

```
Six new concepts, same brief and same two colours, all built around "the same surface used again": a rectangle repeated, offset, mirrored, stacked, rotated or overlapped — each in a different way, always one or two shapes, always stencil-safe.
```

```
Six new concepts, same brief and same two colours, all built from 1-bit pixel patterns on a coarse grid (about 8×10 to 12×14 pixels): shapes that emerge from dither, from a diagonal fade of pixels, from a single missing pixel, from a pixel that steps out of line. No letters.
```

## Step 4 — finalise the one you like

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
| 7 | Not a cliché | Not a leaf, recycling triangle, printer, cloud, folded-corner document or sheet-with-arrow |
| 9 | Has an idea | You can say in one sentence what it means, and it isn't "it's a page" |
| 8 | Ownable | Reverse-image search — nothing close from a known brand |

Ignore completely: letters, canvas proportions, tiny wobbles in the strokes, slight colour drift. All of that is fixed in stage 2.

## Hand-off

Save the finalists in `brand/logo/candidates/` and say which file is the pick (and what you like / dislike about it). Stage 2 traces it, cleans it up, sets the wordmark from Archivo, builds the lockups; stage 3 exports every icon and format (see `README.md` and `prompt-assets.md`).
