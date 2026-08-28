# Stage 3 — asset generation brief

A self-contained brief for an agent (Claude Code or any coding assistant) or a designer. Paste it whole. It assumes the masters from stage 2 exist.

---

```
You are producing the complete logo/icon asset set for the brand "RePaper" from vector masters.
Work in the repository folder brand/logo/. Do not redesign anything; only derive, recolour, lay out, rasterise and package.

INPUTS (masters, already final)
  brand/logo/src/mark.svg              single path, 1024×1024 viewBox, mark centred, ~72 % of the box
  brand/logo/src/wordmark.svg          "REPAPER" as outlines, groups .re and .paper, cap-height 1000 units
  brand/logo/src/lockup-horizontal.svg mark + wordmark side by side (classes .mark .re .paper)
  brand/logo/src/lockup-stacked.svg    mark above wordmark, centred
  brand/tokens.json                    colours (use these hex values, never retype them)

BRAND RULES THAT APPLY TO EVERY FILE
  Colours: Carbon #0C100F, Paper #EFF1EE, Ink #131614, Re Green #1EE3A5 (on dark) / #14C48E (on light).
  The logo is always flat: no glow, gradient, shadow, stroke, texture or 3D — in any file, at any size.
  Colour variants:
    green-on-carbon (primary)  mark & .re = #1EE3A5, .paper = #EFF1EE, background #0C100F
    green-on-paper             mark & .re = #14C48E, .paper = #131614, background #EFF1EE (or transparent)
    mono-paper                 everything #EFF1EE on transparent (for dark grounds)
    mono-ink                   everything #131614 on transparent (for light grounds)
    mono-green                 everything #1EE3A5 on transparent
    mono-black / mono-white    #000000 / #FFFFFF on transparent (print, 1-bit, engraving)
  Clear space: around any lockup, the height of the letter "E". Icons: the mark occupies 60 % of the canvas width, centred (adaptive/maskable icons: 50 %, see below).
  Minimum sizes: mark 16 px; horizontal lockup 90 px wide; below that use the mark alone.
  Never: the wordmark alone on photos, the mark inside a circle or shield, an outline-only version, a rotated version.

OUTPUT TREE  (brand/logo/export/…)
  svg/
    mark-green-on-carbon.svg, mark-green-on-paper.svg, mark-mono-paper.svg, mark-mono-ink.svg, mark-mono-green.svg, mark-mono-black.svg, mark-mono-white.svg
    wordmark-{green-on-carbon,green-on-paper,mono-paper,mono-ink,mono-black,mono-white}.svg
    lockup-horizontal-{…same six…}.svg, lockup-stacked-{…same six…}.svg
    (background included as a rect only in the *-on-carbon / *-on-paper variants; all others transparent)
  png/
    every svg above rasterised at widths 256, 512, 1024, 2048 (lockups: width; mark: square). Transparent unless the variant has a background. Name: <svg-name>-<width>.png
  favicon/
    favicon.svg                 mark-mono-… choose green-on-carbon with the carbon square, 4 px-radius corners at 32 px scale
    favicon.ico                 16, 32, 48 px layers
    favicon-16.png, favicon-32.png, favicon-48.png
    apple-touch-icon.png        180×180, carbon background, no transparency, no rounding (iOS rounds it)
    android-chrome-192.png, android-chrome-512.png   carbon background
    maskable-512.png            carbon background, mark within the central 80 % safe zone (mark at 50 % width)
    site.webmanifest            name "RePaper", short_name "RePaper", theme_color "#0C100F", background_color "#0C100F", icons listed with purpose "any" and "maskable"
    head.html                   the <link>/<meta> snippet to paste into any site: favicon.svg, ico fallback, apple-touch-icon, manifest, theme-color for light (#EFF1EE) and dark (#0C100F)
  app-icon/
    ios/
      AppIcon-1024.png          1024×1024, carbon background, NO alpha channel (App Store rejects alpha)
      AppIcon.appiconset/       Contents.json + PNGs for every slot Xcode 15 needs: 20, 29, 38, 40, 60, 64, 68, 76, 83.5 pt at @2x/@3x as applicable, plus 1024 marketing; use a single 1024 "universal" if targeting iOS 17+ only, otherwise the full set
      dark-1024.png, tinted-1024.png   iOS 18 alternates: dark = mark green on transparent (system adds the dark gradient — this is the one place a system gradient behind our flat mark is acceptable); tinted = mark as greyscale mask
    macos/
      AppIcon.icns              built with `iconutil` from an .iconset of 16,32,64,128,256,512,1024 (+@2x); macOS-style rounded square with the carbon background rendered INTO the artwork (macOS does not mask), corner radius 22.37 % of the size, mark at 56 % width
    android/
      adaptive/ic_launcher_foreground.png   432×432 (108 dp @ xxxhdpi), mark at 50 % width centred, transparent
      adaptive/ic_launcher_background.png   432×432 solid #0C100F   (also provide as a colour resource: <color name="ic_launcher_background">#0C100F</color>)
      adaptive/ic_launcher_monochrome.png   432×432, mark only, #000000 on transparent (Android 13 themed icons)
      mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png   48, 72, 96, 144, 192 px legacy icons, carbon background, 4 px-radius-equivalent rounded corners
      play-store-512.png        512×512, carbon background, no alpha
      ic_launcher.xml           the adaptive-icon XML referencing the layers
    windows/
      app.ico                   16, 24, 32, 48, 64, 128, 256 layers, carbon background
      Square44x44Logo.png, Square150x150Logo.png, Wide310x150Logo.png, StoreLogo.png (50×50)
    pwa/                        same as favicon/android-chrome set + 1024 — symlink or copy
  social/
    avatar-{400,500,800,1024}.png       square, carbon background, mark at 60 %  (X, LinkedIn, GitHub, YouTube, Slack, Discord, Bluesky)
    avatar-lockup-stacked-1024.png     alternative avatar with the stacked lockup, for platforms that show the avatar large
    og-default-1200x630.png            carbon, horizontal lockup centred at ~55 % width, tagline "Reusable paper. Printed the way you already print." in Figtree 500 #A7B0AA below (Figtree from Google Fonts)
    x-header-1500x500.png, linkedin-banner-1128x191.png, youtube-banner-2560x1440.png (safe area 1546×423 centred)
    github-social-preview-1280x640.png
  print/
    lockup-horizontal-{green-on-carbon,mono-black,mono-white}.pdf  vector PDF/X-4, RGB (a CMYK conversion is a print-shop decision; note the chosen Pantone for "Re Green, printed" from brand/tokens.json > print.reGreenPantone when set)
    lockup-horizontal-mono-black.eps, mark-mono-black.eps
    sticker-mark-round-50mm.pdf       green disc (#1EE3A5) with the mark in carbon, 3 mm bleed, dieline on a separate layer
    sticker-lockup-die-cut.pdf        die-cut lockup, mono-white on transparent with a 2 mm carbon keyline
  hardware/
    mark-outline.dxf, mark-outline.svg   the mark's outline as a single closed polyline for emboss / laser / diffuser masks, 1 unit = 1 mm at a 24 mm mark
    mark-1bit/mark-{32,64,128,256}.png   pure 1-bit black on white AND white on black (suffix -inv), no anti-aliasing (nearest-neighbour), for rendering onto e-paper sheets
  manifest.json                 every file with path, variant, size, background, alpha, purpose
  contact-sheet.png             one image showing every icon at its real size on carbon and on paper, for a quick visual check

HOW
  - Rasterise from SVG with librsvg (rsvg-convert), resvg, or sharp — never by screenshot. Anti-alias everything except hardware/mark-1bit/.
  - Build .ico with png-to-ico or ImageMagick; .icns with `iconutil -c icns AppIcon.iconset` on macOS (or png2icns elsewhere).
  - Recolour by replacing the fill of classes .mark .re .paper — never by pixel operations.
  - Fonts: Figtree for the OG tagline only; embed as outlines or ensure the font is available at render time.
  - Verify: run a check that (a) no PNG has a gradient or alpha where a solid background is required, (b) every square icon's mark is centred to the pixel, (c) the 16 px favicon still shows the fold and arrow, (d) manifest.json lists every file in export/. Print the results.
  - Deliver a short report: file count per folder, anything that could not be produced and why, and the three files a human should eyeball first (favicon-16.png, AppIcon-1024.png, og-default-1200x630.png).
```

---

## Notes for running it here

This brief can be executed inside this repo with a Node script (sharp for rasterising, png-to-ico for .ico, `iconutil` for .icns — all present or installable on this Mac), so it does not need an external service. Ask and it gets run as soon as `brand/logo/src/` exists.
