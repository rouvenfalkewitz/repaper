#!/usr/bin/env python3
"""RePaper logo — asset exporter.

Derives every icon / logo / social / print / hardware file from the SVG masters in brand/logo/svg/
into brand/logo/export/. Run build.py first. Everything is composed as vector and rasterised once
(resvg), so edges are exact at every size.

  python3 brand/logo/export.py      (needs: fonttools, uharfbuzz, Pillow, resvg-py, svglib, reportlab; iconutil on macOS)
"""
import io, json, os, re, shutil, subprocess, sys, math, datetime
from PIL import Image
import resvg_py

HERE = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(HERE, "svg")
EXP = os.path.join(HERE, "export")
GEO = json.load(open(os.path.join(HERE, "geometry.json")))
sys.path.insert(0, HERE)
import build  # reuse the font/geometry pipeline (no side effects on import)

CARBON, PAPER, INK, RE400, RE500, MIST = "#0C100F", "#EFF1EE", "#131614", "#1EE3A5", "#14C48E", "#A7B0AA"
TAGLINE = "Reusable paper. Printed the way you already print."
manifest = []

# ── helpers ────────────────────────────────────────────────────────────────────
def master(name):
    """Return (inner_svg_markup_with_inline_fills, W, H) of a master, ready to nest."""
    s = open(os.path.join(SVG, name + ".svg")).read()
    W, H = map(float, re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', s).groups())
    fills = dict(re.findall(r'\.(ring|letters)\{fill:(#[0-9A-Fa-f]{6})\}', s))
    body = re.sub(r'<style>.*?</style>', '', s)
    body = re.sub(r'<!--.*?-->', '', body)
    body = re.sub(r'^<svg[^>]*>', '', body).rsplit('</svg>', 1)[0]
    body = body.replace('class="ring"', f'fill="{fills["ring"]}"').replace('class="letters"', f'fill="{fills["letters"]}"')
    return body, W, H

def nest(name, x, y, w=None, h=None):
    """Nested <svg> of a master, scaled to fit w×h (keeps aspect) at x,y (top-left)."""
    body, W, H = master(name)
    if w is None: w = h * W / H
    if h is None: h = w * H / W
    # a plain group transform (not a nested <svg>) so every renderer — resvg and the PDF writer — scales identically
    return f'<g transform="translate({x:.3f} {y:.3f}) scale({w / W:.6f})">{body}</g>'

def centred(name, cw, ch, frac_w=None, frac_h=None, dx=0, dy=0):
    """Master centred in a cw×ch canvas at frac of width (or height)."""
    _, W, H = master(name)
    if frac_w is not None: w = cw * frac_w; h = w * H / W
    else: h = ch * frac_h; w = h * W / H
    return nest(name, (cw - w) / 2 + dx, (ch - h) / 2 + dy, w, h)

def doc(W, H, body, unit=""):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}{unit}" height="{H}{unit}" viewBox="0 0 {W} {H}">{body}</svg>'

def rrect(W, H, color, rx=0, x=0, y=0):
    return f'<rect x="{x}" y="{y}" width="{W}" height="{H}" rx="{rx}" fill="{color}"/>'

def raster(svg, w, h):
    png = resvg_py.svg_to_bytes(svg_string=svg, width=int(round(w)), height=int(round(h)))
    return Image.open(io.BytesIO(bytes(png))).convert("RGBA")

def save(img, rel, purpose="", alpha=None, **extra):
    path = os.path.join(EXP, rel); os.makedirs(os.path.dirname(path), exist_ok=True)
    if alpha is False and img.mode == "RGBA": img = img.convert("RGB")
    img.save(path)
    manifest.append({"file": rel, "width": img.width, "height": img.height, "alpha": img.mode == "RGBA", "purpose": purpose, **extra})
    return path

def save_text(rel, text, purpose=""):
    path = os.path.join(EXP, rel); os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text); manifest.append({"file": rel, "purpose": purpose})

def save_svg(rel, svg, purpose=""):
    save_text(rel, svg, purpose)

def icon_svg(size, bg, mark="mark-dark", frac=0.60, rx_frac=0.0, inset=0.0):
    """Square icon: optional rounded background (inset from canvas), mark centred at frac of the bg size."""
    s = size * (1 - 2 * inset); o = size * inset
    body = rrect(s, s, bg, rx=s * rx_frac, x=o, y=o) if bg else ""
    body += centred(mark, size, size, frac_w=frac * (1 - 2 * inset))
    return doc(size, size, body)

def icon(size, bg, mark="mark-dark", frac=0.60, rx_frac=0.0, inset=0.0):
    return raster(icon_svg(size, bg, mark, frac, rx_frac, inset), size, size)

# ── text as outlines (Figtree) for the social images ───────────────────────────
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
import uharfbuzz as hb
_fig = instantiateVariableFont(TTFont(os.path.join(HERE, "fonts", "Figtree[wght].ttf")), {"wght": 500})
_fig_upm = _fig["head"].unitsPerEm; _fig_gs = _fig.getGlyphSet()
_b = io.BytesIO(); _fig.save(_b); _fig_hb = hb.Font(hb.Face(hb.Blob(_b.getvalue()))); _fig_hb.scale = (_fig_upm, _fig_upm)
def text_path(text, size_px, color, x, y):
    """Figtree 500 text as a single path, left edge at x, baseline at y. Returns (markup, width_px)."""
    buf = hb.Buffer(); buf.add_str(text); buf.guess_segment_properties(); hb.shape(_fig_hb, buf, {"kern": True})
    k = size_px / _fig_upm; d = []; cx = 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        g = _fig.getGlyphName(info.codepoint)
        pen = SVGPathPen(_fig_gs, ntos=lambda v: f"{v:.2f}")
        _fig_gs[g].draw(TransformPen(pen, Transform(k, 0, 0, -k, x + (cx + pos.x_offset) * k, y)))
        d.append(pen.getCommands()); cx += pos.x_advance
    return f'<path fill="{color}" d="{" ".join(d)}"/>', cx * k

# ── 0. reset ───────────────────────────────────────────────────────────────────
if os.path.exists(EXP): shutil.rmtree(EXP)
os.makedirs(EXP)

# ── 1. svg/ + png/ — every master at four widths ───────────────────────────────
for fn in sorted(os.listdir(SVG)):
    if not fn.endswith(".svg"): continue
    shutil.copy(os.path.join(SVG, fn), os.path.join(EXP, "svg", fn)) if os.path.isdir(os.path.join(EXP, "svg")) else (os.makedirs(os.path.join(EXP, "svg")), shutil.copy(os.path.join(SVG, fn), os.path.join(EXP, "svg", fn)))
    manifest.append({"file": f"svg/{fn}", "purpose": "master vector"})
    name = fn[:-4]; _, W, H = master(name)
    for w in (256, 512, 1024, 2048):
        h = round(w * H / W)
        save(raster(doc(W, H, master(name)[0]), w, h), f"png/{name}-{w}.png", "master raster")

# ── 2. favicon / web ───────────────────────────────────────────────────────────
fav_bg = lambda s, m="ring-dark", f=0.80: icon(s, CARBON, m, frac=f, rx_frac=0.20)
save_svg("favicon/favicon.svg", icon_svg(64, CARBON, "ring-dark", 0.80, rx_frac=0.20), "SVG favicon — the ring on carbon")
fav = {s: fav_bg(s) for s in (16, 32, 48)}
for s, im in fav.items(): save(im, f"favicon/favicon-{s}.png", "favicon")
fav[48].save(os.path.join(EXP, "favicon/favicon.ico"), sizes=[(16, 16), (32, 32), (48, 48)], append_images=[fav[16], fav[32]])
manifest.append({"file": "favicon/favicon.ico", "purpose": "favicon, 16/32/48 layers"})
save(icon(180, CARBON, "mark-dark", 0.62), "favicon/apple-touch-icon.png", "iOS home-screen bookmark (iOS rounds it)", alpha=False)
save(icon(192, CARBON, "mark-dark", 0.62), "favicon/android-chrome-192.png", "PWA / Android Chrome", alpha=False)
save(icon(512, CARBON, "mark-dark", 0.62), "favicon/android-chrome-512.png", "PWA / Android Chrome", alpha=False)
save(icon(512, CARBON, "mark-dark", 0.50), "favicon/maskable-512.png", "PWA maskable (mark inside the 80 % safe zone)", alpha=False)
save_text("favicon/site.webmanifest", json.dumps({
    "name": "RePaper", "short_name": "RePaper", "start_url": "/", "display": "standalone",
    "background_color": CARBON, "theme_color": CARBON,
    "icons": [{"src": "/android-chrome-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
              {"src": "/android-chrome-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
              {"src": "/maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}]}, indent=2), "web app manifest")
save_text("favicon/head.html", f'''<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon.ico" sizes="16x16 32x32 48x48">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<meta name="theme-color" media="(prefers-color-scheme: light)" content="{PAPER}">
<meta name="theme-color" media="(prefers-color-scheme: dark)" content="{CARBON}">
''', "paste into <head>")

# ── 3. app icons ───────────────────────────────────────────────────────────────
# iOS — single-size universal set (Xcode 14+), with iOS 18 dark & tinted appearances
save(icon(1024, CARBON, "mark-dark", 0.60), "app-icon/ios/AppIcon.appiconset/AppIcon-1024.png", "iOS app icon (no alpha)", alpha=False)
save(icon(1024, None, "mark-dark", 0.60), "app-icon/ios/AppIcon.appiconset/AppIcon-dark-1024.png", "iOS 18 dark appearance (system supplies the dark background)")
save(icon(1024, None, "mark-mono-white", 0.60), "app-icon/ios/AppIcon.appiconset/AppIcon-tinted-1024.png", "iOS 18 tinted appearance (greyscale mask)")
save_text("app-icon/ios/AppIcon.appiconset/Contents.json", json.dumps({"images": [
    {"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
    {"appearances": [{"appearance": "luminosity", "value": "dark"}], "filename": "AppIcon-dark-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
    {"appearances": [{"appearance": "luminosity", "value": "tinted"}], "filename": "AppIcon-tinted-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
    "info": {"author": "xcode", "version": 1}}, indent=2), "Xcode asset catalog")
save(icon(1024, CARBON, "mark-dark", 0.60), "app-icon/ios/AppIcon-1024.png", "App Store marketing icon (no alpha)", alpha=False)

# macOS — rounded square inside Apple's 10 % margin, radius 22.37 % of the square, mark at 56 %
mac_svg = lambda s: doc(s, s, rrect(s * 0.805, s * 0.805, CARBON, rx=s * 0.805 * 0.2237, x=s * 0.0975, y=s * 0.0975) + centred("mark-dark", s, s, frac_w=0.56 * 0.805))
iconset = os.path.join(EXP, "app-icon/macos/AppIcon.iconset"); os.makedirs(iconset)
for pt in (16, 32, 128, 256, 512):
    for scale in (1, 2):
        px = pt * scale; name = f"icon_{pt}x{pt}{'@2x' if scale == 2 else ''}.png"
        save(raster(mac_svg(px), px, px), f"app-icon/macos/AppIcon.iconset/{name}", "macOS iconset")
try:
    subprocess.run(["iconutil", "-c", "icns", iconset, "-o", os.path.join(EXP, "app-icon/macos/AppIcon.icns")], check=True)
    manifest.append({"file": "app-icon/macos/AppIcon.icns", "purpose": "macOS app icon"})
except Exception as e:
    manifest.append({"file": "app-icon/macos/AppIcon.icns", "purpose": f"NOT BUILT: {e}"})

# Android — adaptive layers (108 dp @ xxxhdpi = 432 px), legacy mipmaps, Play Store
save(icon(432, None, "mark-dark", 0.50), "app-icon/android/adaptive/ic_launcher_foreground.png", "adaptive foreground (mark in the 66 dp safe zone)")
save(raster(doc(432, 432, rrect(432, 432, CARBON)), 432, 432), "app-icon/android/adaptive/ic_launcher_background.png", "adaptive background", alpha=False)
save(icon(432, None, "mark-mono-white", 0.50), "app-icon/android/adaptive/ic_launcher_monochrome.png", "Android 13 themed-icon layer (alpha only)")
for dpi, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    save(icon(px, CARBON, "mark-dark", 0.60, rx_frac=0.12), f"app-icon/android/mipmap-{dpi}/ic_launcher.png", "legacy launcher icon")
    save(icon(px, CARBON, "mark-dark", 0.60, rx_frac=0.50), f"app-icon/android/mipmap-{dpi}/ic_launcher_round.png", "legacy round launcher icon")
save(icon(512, CARBON, "mark-dark", 0.60), "app-icon/android/play-store-512.png", "Play Store listing icon (no alpha)", alpha=False)
save_text("app-icon/android/mipmap-anydpi-v26/ic_launcher.xml", '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
''', "adaptive icon XML (also use as ic_launcher_round.xml)")
save_text("app-icon/android/values/ic_launcher_background.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<resources><color name="ic_launcher_background">{CARBON}</color></resources>
''', "adaptive background colour resource")

# Windows — multi-layer ICO and Store tiles
win = {s: icon(s, CARBON, "ring-dark" if s < 24 else "mark-dark", 0.80 if s < 24 else 0.60, rx_frac=0.0) for s in (16, 24, 32, 48, 64, 128, 256)}
os.makedirs(os.path.join(EXP, "app-icon/windows"))
win[256].save(os.path.join(EXP, "app-icon/windows/app.ico"), sizes=[(s, s) for s in win], append_images=[win[s] for s in (16, 24, 32, 48, 64, 128)])
manifest.append({"file": "app-icon/windows/app.ico", "purpose": "Windows app icon, 16–256 layers"})
save(icon(44, CARBON, "mark-dark", 0.66), "app-icon/windows/Square44x44Logo.png", "Windows tile")
save(icon(150, CARBON, "mark-dark", 0.56), "app-icon/windows/Square150x150Logo.png", "Windows tile")
save(raster(doc(310, 150, rrect(310, 150, CARBON) + centred("lockup-dark", 310, 150, frac_w=0.70)), 310, 150), "app-icon/windows/Wide310x150Logo.png", "Windows wide tile")
save(icon(50, CARBON, "mark-dark", 0.64), "app-icon/windows/StoreLogo.png", "Windows Store logo")

# ── 4. social ──────────────────────────────────────────────────────────────────
for s in (400, 500, 800, 1024):
    save(icon(s, CARBON, "mark-dark", 0.60), f"social/avatar-{s}.png", "profile avatar (X, LinkedIn, GitHub, YouTube, Slack, Discord, Bluesky)", alpha=False)
save(raster(doc(1024, 1024, rrect(1024, 1024, CARBON) + centred("stacked-dark", 1024, 1024, frac_w=0.62)), 1024, 1024), "social/avatar-lockup-stacked-1024.png", "avatar with the stacked lockup", alpha=False)

def banner(W, H, rel, purpose, lockup_frac_w, tagline=None, tag_size=None, safe=None):
    body = rrect(W, H, CARBON)
    _, lw, lh = master("lockup-dark"); w = W * lockup_frac_w; h = w * lh / lw
    y = (H - h) / 2 - (tag_size * 1.6 if tagline else 0) / 2
    body += nest("lockup-dark", (W - w) / 2, y, w, h)
    if tagline:
        p, tw = text_path(tagline, tag_size, MIST, 0, 0)
        p, tw = text_path(tagline, tag_size, MIST, (W - tw) / 2, y + h + tag_size * 1.6)
        body += p
    save(raster(doc(W, H, body), W, H), rel, purpose, alpha=False)
banner(1200, 630, "social/og-default-1200x630.png", "Open Graph / link preview", 0.52, TAGLINE, 34)
banner(1280, 640, "social/github-social-preview-1280x640.png", "GitHub social preview", 0.52, TAGLINE, 36)
banner(1500, 500, "social/x-header-1500x500.png", "X header", 0.36)
banner(1128, 191, "social/linkedin-banner-1128x191.png", "LinkedIn page banner", 0.24)
banner(2560, 1440, "social/youtube-banner-2560x1440.png", "YouTube banner (lockup inside the 1546×423 safe area)", 0.40)

# ── 5. print — vector PDF via svglib/reportlab ────────────────────────────────
from svglib.svglib import svg2rlg
from reportlab.graphics import renderPDF
def to_pdf(svg, rel, purpose):
    tmp = os.path.join(EXP, "_tmp.svg"); open(tmp, "w").write(svg)
    d = svg2rlg(tmp); os.remove(tmp)
    path = os.path.join(EXP, rel); os.makedirs(os.path.dirname(path), exist_ok=True)
    renderPDF.drawToFile(d, path); manifest.append({"file": rel, "purpose": purpose})
mm = lambda name, width_mm: (lambda b, W, H: doc(width_mm, width_mm * H / W, nest(name, 0, 0, width_mm, width_mm * H / W), "mm"))(*master(name))
to_pdf(mm("lockup-on-carbon", 120), "print/lockup-on-carbon-120mm.pdf", "vector lockup on carbon, 120 mm wide")
to_pdf(mm("lockup-on-paper", 120), "print/lockup-on-paper-120mm.pdf", "vector lockup on paper, 120 mm wide")
to_pdf(mm("lockup-mono-black", 120), "print/lockup-mono-black-120mm.pdf", "vector lockup, black, transparent")
to_pdf(mm("mark-mono-black", 40), "print/mark-mono-black-40mm.pdf", "vector mark, black, transparent")
# stickers (mm): green disc with the mark in carbon; 3 mm bleed; dieline as a 0.1 mm magenta stroke on top
D, B = 50.0, 3.0; S = D + 2 * B
disc = doc(S, S, f'<circle cx="{S/2}" cy="{S/2}" r="{S/2}" fill="{RE400}"/>' + centred("mark-mono-ink", S, S, frac_w=0.60 * D / S)
           + f'<circle cx="{S/2}" cy="{S/2}" r="{D/2}" fill="none" stroke="#FF00FF" stroke-width="0.1"/>', "mm")
to_pdf(disc, "print/sticker-mark-round-50mm.pdf", "round sticker, 50 mm + 3 mm bleed, dieline magenta")
_, lw, lh = master("lockup-dark"); SW = 80.0; SH = SW * lh / lw; PADm = 6.0
diecut = doc(SW + 2 * PADm, SH + 2 * PADm, rrect(SW + 2 * PADm, SH + 2 * PADm, CARBON, rx=PADm) + nest("lockup-dark", PADm, PADm, SW, SH)
             + f'<rect x="0.05" y="0.05" width="{SW+2*PADm-0.1}" height="{SH+2*PADm-0.1}" rx="{PADm}" fill="none" stroke="#FF00FF" stroke-width="0.1"/>', "mm")
to_pdf(diecut, "print/sticker-lockup-diecut-80mm.pdf", "die-cut lockup sticker on carbon, 80 mm wide, dieline magenta")

# ── 6. hardware ────────────────────────────────────────────────────────────────
save_svg("hardware/mark-outline.svg", open(os.path.join(SVG, "mark-mono-black.svg")).read(), "single-colour outline for emboss / laser / diffuser masks")
# DXF: flattened closed polylines, 1 unit = 1 mm, ring outer diameter = 24 mm
def dxf():
    k = 24.0 / (2 * build.R_OUT); cx, cy = build.CX, build.CY
    polys = []
    for r in (build.R_OUT, build.R_IN):
        polys.append([(cx + r * math.cos(2 * math.pi * i / 180), cy + r * math.sin(2 * math.pi * i / 180)) for i in range(180)])
    from fontTools.pens.recordingPen import RecordingPen
    for g, x in build.RE:
        rp = RecordingPen(); build.gs[g].draw(rp); cur = []
        for op, args in rp.value:
            if op == "moveTo": cur = [(args[0][0] + x, args[0][1])]
            elif op == "lineTo": cur.append((args[0][0] + x, args[0][1]))
            elif op == "qCurveTo":
                pts = [(p[0] + x, p[1]) for p in args]; offs, last = pts[:-1], pts[-1]; p0 = cur[-1]
                for i, o in enumerate(offs):
                    e = last if i == len(offs) - 1 else ((o[0] + offs[i+1][0]) / 2, (o[1] + offs[i+1][1]) / 2)
                    for kk in range(1, 9):
                        t = kk / 8; u = 1 - t; cur.append((u*u*p0[0] + 2*u*t*o[0] + t*t*e[0], u*u*p0[1] + 2*u*t*o[1] + t*t*e[1]))
                    p0 = e
            elif op in ("closePath", "endPath"): polys.append(cur); cur = []
    out = ["0", "SECTION", "2", "ENTITIES"]
    for poly in polys:
        out += ["0", "LWPOLYLINE", "8", "MARK", "90", str(len(poly)), "70", "1"]
        for (px, py) in poly: out += ["10", f"{(px - cx) * k:.4f}", "20", f"{(py - cy) * k:.4f}"]
    out += ["0", "ENDSEC", "0", "EOF"]
    return "\n".join(out) + "\n"
save_text("hardware/mark-outline-24mm.dxf", dxf(), "DXF polylines, 1 unit = 1 mm, ring Ø 24 mm, origin at ring centre")
for s in (32, 64, 128, 256):
    im = raster(doc(s, s, rrect(s, s, "#FFFFFF") + centred("mark-mono-black", s, s, frac_w=0.84)), s, s).convert("L").point(lambda v: 255 if v > 127 else 0).convert("1")
    save(im, f"hardware/mark-1bit/mark-{s}.png", "1-bit black on white, for e-paper rendering")
    save(im.convert("L").point(lambda v: 255 - v).convert("1"), f"hardware/mark-1bit/mark-{s}-inv.png", "1-bit white on black")

# ── 7. contact sheet + manifest + checks ───────────────────────────────────────
sheet_items = [("favicon/favicon-16.png", 16), ("favicon/favicon-32.png", 32), ("favicon/favicon-48.png", 48), ("favicon/apple-touch-icon.png", 180),
               ("app-icon/ios/AppIcon-1024.png", 180), ("app-icon/macos/AppIcon.iconset/icon_256x256.png", 180), ("app-icon/android/mipmap-xxxhdpi/ic_launcher.png", 180),
               ("app-icon/android/mipmap-xxxhdpi/ic_launcher_round.png", 180), ("social/avatar-400.png", 180), ("social/og-default-1200x630.png", 420)]
sheet = Image.new("RGBA", (1400, 560), CARBON); x = 24
for rel, w in sheet_items:
    im = Image.open(os.path.join(EXP, rel)).convert("RGBA"); h = round(w * im.height / im.width)
    im = im.resize((w, h), Image.LANCZOS if w < im.width else Image.NEAREST); sheet.paste(im, (x, 24), im); x += w + 24
paper_row = Image.new("RGBA", (1400, 260), PAPER); x = 24
for rel, w in [("png/lockup-light-1024.png", 420), ("png/mark-light-256.png", 180), ("png/ring-light-256.png", 180), ("png/wordmark-light-1024.png", 420)]:
    im = Image.open(os.path.join(EXP, rel)).convert("RGBA"); h = round(w * im.height / im.width); im = im.resize((w, h), Image.LANCZOS)
    paper_row.paste(im, (x, (260 - h) // 2), im); x += w + 24
sheet.paste(paper_row, (0, 300)); save(sheet.convert("RGB"), "contact-sheet.png", "quick visual check")

problems = []
for m in manifest:
    p = os.path.join(EXP, m["file"])
    if not os.path.exists(p): problems.append(f"missing: {m['file']}")
for rel in ("app-icon/ios/AppIcon-1024.png", "app-icon/android/play-store-512.png", "favicon/apple-touch-icon.png", "social/avatar-400.png"):
    if Image.open(os.path.join(EXP, rel)).mode != "RGB": problems.append(f"alpha present: {rel}")
if not os.path.exists(os.path.join(EXP, "favicon/favicon-16.png")): problems.append("no 16 px favicon")
manifest.sort(key=lambda m: m["file"])
json.dump({"brand": "RePaper", "generated": datetime.date.today().isoformat(), "source": "brand/logo/svg via export.py", "geometry": GEO["ring"], "files": manifest, "problems": problems},
          open(os.path.join(EXP, "manifest.json"), "w"), indent=2)
by_dir = {}
for m in manifest: by_dir[m["file"].split("/")[0]] = by_dir.get(m["file"].split("/")[0], 0) + 1
print(json.dumps(by_dir), "| total", len(manifest), "| problems:", problems or "none")
