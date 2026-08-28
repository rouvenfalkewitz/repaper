# RePaper logo

**The light ring around RE, followed by PAPER.** The ring is the Dock's ring — the one shape that exists physically, on screen and in the logo — and it carries the brand colour. The letters stay Paper (on dark) or Ink (on light).

Everything here is generated. Nothing is drawn by hand.

```
fonts/         Archivo + Figtree variable TTFs (OFL) — the only inputs besides numbers
build.py       font + numbers → svg/  (the masters)
geometry.json  the numbers, as measured/derived by build.py
export.py      svg/ → export/  (every icon, raster, social, print and hardware file)
svg/           50 masters: {lockup,stacked,mark,ring,wordmark}-{variant}.svg
export/        325 files — see export/manifest.json
```

## Compositions

| | Use |
|---|---|
| `lockup` | Default. Websites, documents, signage, packaging, the Dock's setup page. |
| `stacked` | Square-ish spaces: social avatars, badges, the back of a tee. |
| `mark` | Ring + RE. App icons, favicons ≥ 32 px, stickers, the Dock's diffuser. |
| `ring` | The ring alone. Favicon at 16 px, LED renders, pattern element. |
| `wordmark` | Only where a circle can't work — narrow strips, thin-edge engraving. Single colour. |

## Colour variants

| Variant | Ring | Letters | Background | Use |
|---|---|---|---|---|
| `on-carbon` | #1EE3A5 | #EFF1EE | #0C100F, padded by the clear space | ready-to-place on anything |
| `on-paper` | #14C48E | #131614 | #EFF1EE, padded | ready-to-place, light |
| `dark` | #1EE3A5 | #EFF1EE | transparent | on dark grounds |
| `light` | #14C48E | #131614 | transparent | on light grounds |
| `mono-re400` / `mono-re500` | all #1EE3A5 / #14C48E | | transparent | one-colour green |
| `mono-paper` / `mono-ink` | all #EFF1EE / #131614 | | transparent | one-colour on dark / light |
| `mono-black` / `mono-white` | all #000 / #FFF | | transparent | 1-bit, print, engraving |

Rule: **the green never goes on the letters** — except in the mono versions, where everything is one colour.

## Geometry (from `geometry.json`)

- Archivo, width 125, weight 800, caps, tracking −0.03 em; cap height 686 (UPM 1000)
- Ring stroke = stem width of the R (203.6 = 0.297 × cap), measured from the outlines
- Ring centre: x = centre of the RE bounding box, y = cap height / 2
- Ring clearance to the letters: 12 % of cap; ring outer diameter: 3.409 × cap
- Gap ring → P: 22 % of cap; stacked gap: 22 % of cap
- Clear space around any composition: 1 × cap height
- Minimum sizes: lockup cap 24 px (≈ 82 px tall), stacked 96 px wide, mark 32 px, ring 16 px

## Rebuilding

```
python3 -m venv .venv && .venv/bin/pip install fonttools uharfbuzz Pillow resvg-py svglib reportlab
.venv/bin/python brand/logo/build.py     # masters
.venv/bin/python brand/logo/export.py    # everything else (iconutil for .icns is macOS-only)
```

Change a number at the top of `build.py`, run both, commit. `export/manifest.json` lists every file with size, alpha and purpose; `export/contact-sheet.png` is the quick visual check.

## What's in `export/`

- `svg/` the 50 masters · `png/` each at 256 / 512 / 1024 / 2048 wide
- `favicon/` favicon.svg + .ico (16/32/48) + PNGs, apple-touch-icon, android-chrome 192/512, maskable, `site.webmanifest`, `head.html`
- `app-icon/ios/` 1024 (no alpha) + Xcode asset catalog with iOS 18 dark & tinted appearances · `macos/` .icns + iconset · `android/` adaptive layers (foreground/background/monochrome), legacy mipmaps, Play Store 512, XML · `windows/` app.ico (16–256) + Store tiles
- `social/` avatars 400–1024, stacked avatar, OG 1200×630 with tagline, GitHub preview, X header, LinkedIn banner, YouTube banner
- `print/` vector PDFs (lockup on carbon / on paper / mono black, mark mono black; 50 mm round sticker and 80 mm die-cut sticker with magenta dielines)
- `hardware/` single-colour outline SVG, DXF (ring Ø 24 mm, origin at the ring centre) for emboss / laser / diffuser masks, 1-bit PNGs 32–256 (black-on-white and inverted) for e-paper rendering
