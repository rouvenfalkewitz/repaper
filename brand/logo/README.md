# RePaper logo pipeline

Three stages. Stage 1 needs a human with an image model; stages 2 and 3 are mechanical and can be done here in the repo.

```
1. Generate     prompt-logo.md  → an image model → candidates (PNG)  → pick one
2. Vectorise    mark: trace the PNG → clean SVG · wordmark: set from Archivo → outlines · combine
3. Assets       prompt-assets.md → every logo / icon / social / print file, from the SVGs
```

## The one thing to know before stage 1

**The image model only has to get the mark right.** Image models are unreliable at letterforms, and we don't need theirs: the wordmark is fully specified (Archivo Extra-Bold, width 125, caps, tracking −3 %, "RE" in Re Green, "PAPER" in Paper/Ink) and gets re-set from the real font in stage 2. Judge candidates by the mark and by the *proportions* of the lockup, never by the letters. A candidate with a perfect mark and a mangled "REPAPER" is a winner.

## Stage 1 — generate

Use `prompt-logo.md`. Run the **lockup** prompt first (it shows the mark in context), then the **mark-only** prompt on the direction you like. Ask for 2048 px or the largest square the tool offers, and keep the PNGs in `brand/logo/candidates/` (named `a-01.png`, `a-02.png`, … by direction). Score them with the checklist at the bottom of `prompt-logo.md`; keep the top two.

## Stage 2 — vectorise

The mark is traced; the wordmark is typeset. Never trace text.

**Mark (PNG → SVG)**
1. Crop the mark tightly, keep at least 1024 px on the short side (upscale 2× with a plain bicubic filter if needed; no "AI upscaling", it invents detail).
2. Threshold to pure black and white (level ~50 %). If the mark was green on carbon, invert first so the mark is black.
3. Trace: `potrace mark.pbm -s -o mark-raw.svg --turdsize 20 --alphamax 1 --opttolerance 0.4` (or `vtracer --input mark.png --output mark-raw.svg --mode polygon --filter_speckle 20` for a more geometric result).
4. Clean up in a vector editor (Inkscape / Illustrator / Figma): simplify nodes, make straight edges truly straight and equal strokes truly equal, snap to a 24-unit grid, centre in a square 1024 × 1024 viewBox with the mark filling ~72 % of it. Single path, single fill, no strokes. Save as `mark.svg`.
5. Test: 16 px, 1-bit, and filled solid — all must still read.

**Wordmark (font → SVG)**
1. Fetch Archivo (OFL licence; conversion to outlines for a logo is permitted): `brand/logo/fonts/Archivo[wdth,wght].ttf` from Google Fonts.
2. Set `REPAPER` at weight 800, width 125, tracking −0.03 em, convert to outlines. Two groups: `RE` and `PAPER`, so colours can be applied per group.
3. Save as `wordmark.svg` (viewBox tight to the glyphs, cap-height = 1000 units).

**Lockup**
Mark left of the wordmark; mark height = cap height; gap = width of "E" × 0.6; clear space = height of "E". Save `lockup-horizontal.svg` and `lockup-stacked.svg` (mark above, wordmark below, both centred). Colours are applied by class (`.re`, `.paper`, `.mark`) so stage 3 can recolour by swapping a stylesheet.

**Tools on this machine right now:** `iconutil`, `sips`, Node 20, Python 3 — no tracer. One-time setup for stages 2–3:
```
brew install potrace librsvg imagemagick
python3 -m pip install pillow
npm i -g sharp-cli png-to-ico   # or use the Node script from stage 3
```

## Stage 3 — assets

`prompt-assets.md` is a complete, self-contained brief for an agent (Claude Code or otherwise) — or for a human — that takes `mark.svg` + `wordmark.svg` and produces every file listed there, with a manifest and a contact sheet. It can be run here in the repo once the SVGs exist.

## Final folder layout

```
brand/logo/
  README.md · prompt-logo.md · prompt-assets.md
  candidates/            stage 1 PNGs (kept for the record)
  fonts/                 Archivo variable TTF (OFL)
  src/                   mark.svg · wordmark.svg · lockup-horizontal.svg · lockup-stacked.svg   ← the masters
  export/                everything stage 3 produces (see prompt-assets.md for the tree)
```
