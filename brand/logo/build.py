#!/usr/bin/env python3
"""RePaper logo — master builder.

Generates every logo SVG in brand/logo/svg/ from the Archivo font file and a handful of
numbers. Nothing is drawn by hand: change a constant, re-run, every file updates.

  python3 brand/logo/build.py            (needs: fonttools, uharfbuzz)

Compositions   lockup · stacked · mark · ring · wordmark
Colour variants on-carbon · on-paper · dark · light · mono-re400 · mono-re500 · mono-paper ·
               mono-ink · mono-black · mono-white
"""
import io, json, math, os, re
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.misc.transform import Transform
import uharfbuzz as hb

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "fonts", "Archivo[wdth,wght].ttf")
OUT = os.path.join(HERE, "svg")

# ── The numbers that define the logo ───────────────────────────────────────────
WDTH, WGHT = 125, 800          # Archivo axes: extra-wide, extra-bold
TRACK = -0.03                  # tracking, em
RING_CLEAR = 0.12              # clearance between ring inner edge and letters, × cap height
RING_GAP = 0.22                # gap between ring outer edge and the P of PAPER, × cap height
STACK_GAP = 0.22               # gap between ring and PAPER in the stacked lockup, × cap height
CLEAR_SPACE = 1.0              # protected space around any composition, × cap height (bg variants are padded by this)

# ── Colours (brand/tokens.json) ────────────────────────────────────────────────
RE400, RE500 = "#1EE3A5", "#14C48E"
CARBON, PAPER, INK = "#0C100F", "#EFF1EE", "#131614"
BLACK, WHITE = "#000000", "#FFFFFF"
VARIANTS = {
    "on-carbon":  dict(ring=RE400, letters=PAPER, bg=CARBON),
    "on-paper":   dict(ring=RE500, letters=INK,   bg=PAPER),
    "dark":       dict(ring=RE400, letters=PAPER),
    "light":      dict(ring=RE500, letters=INK),
    "mono-re400": dict(ring=RE400, letters=RE400),
    "mono-re500": dict(ring=RE500, letters=RE500),
    "mono-paper": dict(ring=PAPER, letters=PAPER),
    "mono-ink":   dict(ring=INK,   letters=INK),
    "mono-black": dict(ring=BLACK, letters=BLACK),
    "mono-white": dict(ring=WHITE, letters=WHITE),
}

# ── Font ───────────────────────────────────────────────────────────────────────
vf = TTFont(FONT)
font = instantiateVariableFont(vf, {"wdth": WDTH, "wght": WGHT})
UPM = font["head"].unitsPerEm
CAP = font["OS/2"].sCapHeight
gs = font.getGlyphSet()
_buf = io.BytesIO(); font.save(_buf)
hbfont = hb.Font(hb.Face(hb.Blob(_buf.getvalue()))); hbfont.scale = (UPM, UPM)

def shape(text):
    """[(glyphName, x)] in font units, kerned, with tracking applied; plus total advance."""
    b = hb.Buffer(); b.add_str(text); b.guess_segment_properties()
    hb.shape(hbfont, b, {"kern": True, "liga": False})
    out, x = [], 0.0
    for info, pos in zip(b.glyph_infos, b.glyph_positions):
        out.append((font.getGlyphName(info.codepoint), x + pos.x_offset))
        x += pos.x_advance + TRACK * UPM
    return out, x - TRACK * UPM

def bounds(glyphs, dx=0.0):
    bp = BoundsPen(gs)
    for g, x in glyphs:
        TransformPen(bp, Transform(1, 0, 0, 1, x + dx, 0)); gs[g].draw(TransformPen(bp, Transform(1, 0, 0, 1, x + dx, 0)))
    return bp.bounds  # xmin, ymin, xmax, ymax (y-up)

def flat_points(glyphs, dx=0.0, steps=12):
    """Flattened outline points (y-up) — used to place the ring exactly around the letters."""
    pts = []
    for g, x in glyphs:
        rp = RecordingPen(); gs[g].draw(TransformPen(rp, Transform(1, 0, 0, 1, x + dx, 0)))
        cur = None
        for op, args in rp.value:
            if op == "moveTo": cur = args[0]; pts.append(cur)
            elif op == "lineTo": cur = args[0]; pts.append(cur)
            elif op == "qCurveTo":
                pts_ = list(args); offs, last = pts_[:-1], pts_[-1]
                p0 = cur
                for i, o in enumerate(offs):
                    e = last if i == len(offs) - 1 else ((o[0] + offs[i + 1][0]) / 2, (o[1] + offs[i + 1][1]) / 2)
                    for k in range(1, steps + 1):
                        t = k / steps; u = 1 - t
                        pts.append((u * u * p0[0] + 2 * u * t * o[0] + t * t * e[0], u * u * p0[1] + 2 * u * t * o[1] + t * t * e[1]))
                    p0 = e
                cur = last
            elif op == "curveTo":
                p1, p2, p3 = args; p0 = cur
                for k in range(1, steps + 1):
                    t = k / steps; u = 1 - t
                    pts.append((u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                                u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
                cur = p3
    return pts

def stem_width(glyph="R", at=0.30):
    """Horizontal thickness of the R's stem at `at` × cap height — the ring's stroke width."""
    pts = flat_points([(glyph, 0.0)]); y = CAP * at
    # rebuild contours for an even-odd scan: approximate by scanning all edges of the flattened polyline
    rp = RecordingPen(); gs[glyph].draw(rp)
    contours, cur = [], []
    for op, args in rp.value:
        if op == "moveTo": cur = [args[0]]
        elif op == "lineTo": cur.append(args[0])
        elif op == "qCurveTo":
            pts_ = list(args); offs, last = pts_[:-1], pts_[-1]; p0 = cur[-1]
            for i, o in enumerate(offs):
                e = last if i == len(offs) - 1 else ((o[0] + offs[i + 1][0]) / 2, (o[1] + offs[i + 1][1]) / 2)
                for k in range(1, 13):
                    t = k / 12; u = 1 - t
                    cur.append((u * u * p0[0] + 2 * u * t * o[0] + t * t * e[0], u * u * p0[1] + 2 * u * t * o[1] + t * t * e[1]))
                p0 = e
        elif op == "curveTo":
            p1, p2, p3 = args; p0 = cur[-1]
            for k in range(1, 13):
                t = k / 12; u = 1 - t
                cur.append((u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0], u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
        elif op in ("closePath", "endPath"): contours.append(cur); cur = []
    xs = []
    for c in contours:
        for i in range(len(c)):
            (x1, y1), (x2, y2) = c[i], c[(i + 1) % len(c)]
            if (y1 <= y < y2) or (y2 <= y < y1): xs.append(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
    xs.sort()
    return xs[1] - xs[0]

# ── Geometry ───────────────────────────────────────────────────────────────────
RE, RE_ADV = shape("RE")
PA, PA_ADV = shape("PAPER")
WM, WM_ADV = shape("REPAPER")
STEM = stem_width()
re_pts = flat_points(RE)
re_xmin, re_ymin, re_xmax, re_ymax = bounds(RE)
CX, CY = (re_xmin + re_xmax) / 2, CAP / 2                      # ring centre (y-up)
R_IN = max(math.hypot(x - CX, y - CY) for x, y in re_pts) + RING_CLEAR * CAP
R_OUT = R_IN + STEM
pa_xmin = bounds(PA)[0]
PA_DX = (CX + R_OUT + RING_GAP * CAP) - pa_xmin                  # x offset for PAPER in the lockup

# ── SVG helpers ────────────────────────────────────────────────────────────────
def fmt(v): return f"{v:.1f}".rstrip("0").rstrip(".")

def glyph_paths(glyphs, T):
    d = []
    for g, x in glyphs:
        pen = SVGPathPen(gs, ntos=fmt); gs[g].draw(TransformPen(pen, T.translate(x, 0)))
        d.append(pen.getCommands())
    return " ".join(d)

def circle_d(cx, cy, r):
    return f"M{fmt(cx - r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0Z"

def ring_d(cx, cy):
    return circle_d(cx, cy, R_OUT) + circle_d(cx, cy, R_IN)

class Comp:
    """A composition in y-up font space: list of parts + bbox. Parts: ('ring', cx, cy) | ('letters', glyphs, dx)."""
    def __init__(self, parts, bbox): self.parts, self.bbox = parts, bbox

def comp_lockup():
    x0, y0 = CX - R_OUT, CY - R_OUT
    x1, y1 = bounds(PA, PA_DX)[2], CY + R_OUT
    return Comp([("ring", CX, CY), ("letters", RE, 0.0), ("letters", PA, PA_DX)], (x0, y0, x1, y1))

def comp_mark():
    return Comp([("ring", CX, CY), ("letters", RE, 0.0)], (CX - R_OUT, CY - R_OUT, CX + R_OUT, CY + R_OUT))

def comp_ring():
    return Comp([("ring", CX, CY)], (CX - R_OUT, CY - R_OUT, CX + R_OUT, CY + R_OUT))

def comp_wordmark():
    b = bounds(WM); return Comp([("letters", WM, 0.0)], (b[0], 0.0, b[2], CAP))

def comp_stacked():
    pb = bounds(PA); pw = pb[2] - pb[0]; mw = 2 * R_OUT
    total = max(pw, mw); mid = pb[0] + pw / 2                    # centre on PAPER
    ring_cy = CAP + STACK_GAP * CAP + R_OUT
    re_dx = mid - CX                                             # shift ring+RE so the ring centre is on `mid`
    re_dy = ring_cy - CY
    parts = [("ring", mid, ring_cy), ("letters_dy", RE, re_dx, re_dy), ("letters", PA, 0.0)]
    return Comp(parts, (mid - total / 2, 0.0, mid + total / 2, ring_cy + R_OUT))

COMPS = {"lockup": comp_lockup, "stacked": comp_stacked, "mark": comp_mark, "ring": comp_ring, "wordmark": comp_wordmark}

def render(name, comp, variant, spec):
    x0, y0, x1, y1 = comp.bbox
    pad = CLEAR_SPACE * CAP if "bg" in spec else 0.0
    W, H = (x1 - x0) + 2 * pad, (y1 - y0) + 2 * pad
    # y-up font space → y-down SVG space, origin at the padded top-left
    T = Transform(1, 0, 0, -1, -x0 + pad, y1 + pad)
    body = []
    if "bg" in spec:
        body.append(f'<rect width="{fmt(W)}" height="{fmt(H)}" fill="{spec["bg"]}"/>')
    for p in comp.parts:
        if p[0] == "ring":
            cx, cy = T.transformPoint((p[1], p[2]))
            body.append(f'<path class="ring" fill-rule="evenodd" d="{ring_d(cx, cy)}"/>')
        elif p[0] == "letters":
            body.append(f'<path class="letters" d="{glyph_paths(p[1], T.translate(p[2], 0))}"/>')
        elif p[0] == "letters_dy":
            body.append(f'<path class="letters" d="{glyph_paths(p[1], T.translate(p[2], p[3]))}"/>')
    style = f'<style>.ring{{fill:{spec["ring"]}}}.letters{{fill:{spec["letters"]}}}</style>'
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(W)} {fmt(H)}">'
           f'<!-- RePaper logo · {name} · {variant} · generated by brand/logo/build.py — do not edit by hand -->'
           f'{style}{"".join(body)}</svg>')
    return svg, (W, H)

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT): os.remove(os.path.join(OUT, f))
    index = {}
    for cname, fn in COMPS.items():
        comp = fn()
        for vname, spec in VARIANTS.items():
            svg, (W, H) = render(cname, comp, vname, spec)
            path = os.path.join(OUT, f"{cname}-{vname}.svg")
            open(path, "w").write(svg)
            index[f"{cname}-{vname}.svg"] = {"composition": cname, "variant": vname, "width": round(W, 1), "height": round(H, 1),
                                             "background": spec.get("bg"), "ring": spec["ring"], "letters": spec["letters"]}
    lock = comp_lockup(); lb = lock.bbox
    geometry = {
        "font": {"family": "Archivo", "wdth": WDTH, "wght": WGHT, "tracking_em": TRACK, "unitsPerEm": UPM, "capHeight": CAP},
        "ring": {"stroke": round(STEM, 1), "stroke_over_cap": round(STEM / CAP, 4), "inner_radius": round(R_IN, 1), "outer_radius": round(R_OUT, 1),
                 "outer_diameter_over_cap": round(2 * R_OUT / CAP, 4), "clearance_over_cap": RING_CLEAR,
                 "centre": "x = centre of the RE bounding box, y = cap height / 2"},
        "lockup": {"gap_ring_to_P_over_cap": RING_GAP, "width": round(lb[2] - lb[0], 1), "height": round(lb[3] - lb[1], 1),
                   "aspect": round((lb[2] - lb[0]) / (lb[3] - lb[1]), 4), "baseline_from_top_over_height": round((lb[3] - 0) / (lb[3] - lb[1]), 4)},
        "stacked": {"gap_over_cap": STACK_GAP},
        "clear_space_over_cap": CLEAR_SPACE,
        "minimum_sizes_px": {"lockup_cap_height": 24, "lockup_height": round(24 * 2 * R_OUT / CAP), "mark": 24, "ring_only": 16},
        "files": index,
    }
    json.dump(geometry, open(os.path.join(HERE, "geometry.json"), "w"), indent=2)
    print(f"{len(index)} SVGs · cap {CAP} · stem {STEM:.1f} · ring Ø {2*R_OUT/CAP:.3f}×cap · lockup {lb[2]-lb[0]:.0f}×{lb[3]-lb[1]:.0f}")
