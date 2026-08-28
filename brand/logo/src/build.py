import math, json, sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.basePen import BasePen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
import uharfbuzz as hb

FONT="/Users/rouvenfalkewitz/Projects/Rouven/Repaper/brand/logo/fonts/Archivo[wdth,wght].ttf"
OUT="/Users/rouvenfalkewitz/Projects/Rouven/Repaper/brand/logo/src"
G="#1EE3A5"; C="#0C100F"; P="#EFF1EE"
WDTH, WGHT, TRACK = 125, 800, -0.03

vf=TTFont(FONT)
font=instantiateVariableFont(vf, {"wdth":WDTH,"wght":WGHT})
UPM=font["head"].unitsPerEm; CAP=font["OS/2"].sCapHeight
gs=font.getGlyphSet(); cmap=font.getBestCmap()
# shape with harfbuzz (kerning), using the static instance
import io; buf=io.BytesIO(); font.save(buf); blob=hb.Blob(buf.getvalue()); face=hb.Face(blob); hbf=hb.Font(face); hbf.scale=(UPM,UPM)
def shape(text):
    b=hb.Buffer(); b.add_str(text); b.guess_segment_properties(); hb.shape(hbf,b,{"kern":True,"liga":False})
    out=[]; x=0
    for info,pos in zip(b.glyph_infos,b.glyph_positions):
        gname=font.getGlyphName(info.codepoint)
        out.append((gname, x+pos.x_offset)); x+=pos.x_advance + TRACK*UPM
    return out, x

class FlatPen(BasePen):
    def __init__(self,gs): super().__init__(gs); self.pts=[]
    def _moveTo(self,p): self.pts.append(p)
    def _lineTo(self,p): self.pts.append(p)
    def _curveToOne(self,p1,p2,p3):
        p0=self._getCurrentPoint()
        for k in range(1,9):
            t=k/8; u=1-t
            self.pts.append((u**3*p0[0]+3*u*u*t*p1[0]+3*u*t*t*p2[0]+t**3*p3[0], u**3*p0[1]+3*u*u*t*p1[1]+3*u*t*t*p2[1]+t**3*p3[1]))
    def _qCurveToOne(self,p1,p2):
        p0=self._getCurrentPoint()
        for k in range(1,9):
            t=k/8; u=1-t
            self.pts.append((u*u*p0[0]+2*u*t*p1[0]+t*t*p2[0], u*u*p0[1]+2*u*t*p1[1]+t*t*p2[1]))
    def _closePath(self): pass

def glyph_path(gname, dx):
    pen=SVGPathPen(gs); gs[gname].draw(pen); return pen.getCommands(), dx
def glyph_points(gname, dx):
    pen=FlatPen(gs); gs[gname].draw(pen); return [(x+dx,y) for (x,y) in pen.pts]

re_glyphs, re_adv = shape("RE")
pa_glyphs, pa_adv = shape("PAPER")
# stem width of R: scan a horizontal line at 0.3 cap through the flattened R contour (even-odd)
def stem_width(gname):
    pen=FlatPen(gs); g=gs[gname]
    # need contours separately: use a recording of segments
    from fontTools.pens.recordingPen import RecordingPen
    rp=RecordingPen(); g.draw(rp)
    contours=[]; cur=[]
    for op,args in rp.value:
        if op=="moveTo": cur=[args[0]]
        elif op=="lineTo": cur.append(args[0])
        elif op=="qCurveTo":
            pts=list(args); p0=cur[-1]
            # implied on-curve points between consecutive off-curves
            offs=pts[:-1]; last=pts[-1]
            seq=[]
            for i,o in enumerate(offs):
                if i<len(offs)-1: seq.append((o, ((o[0]+offs[i+1][0])/2,(o[1]+offs[i+1][1])/2)))
                else: seq.append((o,last))
            for (o,e) in seq:
                for k in range(1,9):
                    t=k/8; u=1-t; cur.append((u*u*p0[0]+2*u*t*o[0]+t*t*e[0], u*u*p0[1]+2*u*t*o[1]+t*t*e[1]))
                p0=e
        elif op=="curveTo":
            p1,p2,p3=args; p0=cur[-1]
            for k in range(1,9):
                t=k/8; u=1-t; cur.append((u**3*p0[0]+3*u*u*t*p1[0]+3*u*t*t*p2[0]+t**3*p3[0], u**3*p0[1]+3*u*u*t*p1[1]+3*u*t*t*p2[1]+t**3*p3[1]))
        elif op in ("closePath","endPath"): contours.append(cur); cur=[]
    y=CAP*0.3; xs=[]
    for c in contours:
        for i in range(len(c)):
            (x1,y1),(x2,y2)=c[i],c[(i+1)%len(c)]
            if (y1<=y<y2) or (y2<=y<y1): xs.append(x1+(y-y1)*(x2-x1)/(y2-y1))
    xs.sort(); return xs[1]-xs[0] if len(xs)>=2 else CAP*0.16
STEM=stem_width("R")
# RE geometry
pts=[]; 
for gname,dx in re_glyphs: pts+=glyph_points(gname,dx)
bp=BoundsPen(gs)
xmin=min(p[0] for p in pts); xmax=max(p[0] for p in pts)
cx=(xmin+xmax)/2; cy=CAP/2
maxd=max(math.hypot(x-cx,y-cy) for (x,y) in pts)
CLEAR=0.12*CAP
r_in=maxd+CLEAR; r_mid=r_in+STEM/2; r_out=r_in+STEM
GAP=0.22*CAP                     # space between ring and P
pa_x0=cx+r_out+GAP - (min(p[0] for p in glyph_points(pa_glyphs[0][0],pa_glyphs[0][1])))
pa_pts=[]
for gname,dx in pa_glyphs: pa_pts+=glyph_points(gname,dx+pa_x0)
total_xmax=max(p[0] for p in pa_pts)
M=0.15*CAP                       # margin
left=cx-r_out-M; right=total_xmax+M; top=cy+r_out+M; bottom=cy-r_out-M
W=right-left; H=top-bottom
def paths(glyphs, offset, cls):
    s=f'<g class="{cls}">'
    for gname,dx in glyphs:
        d,_=glyph_path(gname,dx); s+=f'<path transform="translate({dx+offset:.1f} 0)" d="{d}"/>'
    return s+'</g>'
def svg_lockup(re_col, pa_col, ring_col, bg=None, vb=None):
    vb = vb or (left,bottom,W,H)
    s=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb[0]:.1f} {-(vb[1]+vb[3]):.1f} {vb[2]:.1f} {vb[3]:.1f}">'
    if bg: s+=f'<rect x="{vb[0]:.1f}" y="{-(vb[1]+vb[3]):.1f}" width="{vb[2]:.1f}" height="{vb[3]:.1f}" fill="{bg}"/>'
    s+=f'<style>.re{{fill:{re_col}}}.pa{{fill:{pa_col}}}.ring{{fill:none;stroke:{ring_col};stroke-width:{STEM:.1f}}}</style>'
    s+='<g transform="scale(1 -1)">'
    s+=f'<circle class="ring" cx="{cx:.1f}" cy="{cy:.1f}" r="{r_mid:.1f}"/>'
    s+=paths(re_glyphs,0,"re")+paths(pa_glyphs,pa_x0,"pa")
    s+='</g></svg>'
    return s
def svg_mark(re_col, ring_col, bg=None):
    R=r_out+0.35*CAP; vb=(cx-R,cy-R,2*R,2*R)
    s=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb[0]:.1f} {-(vb[1]+vb[3]):.1f} {vb[2]:.1f} {vb[3]:.1f}">'
    if bg: s+=f'<rect x="{vb[0]:.1f}" y="{-(vb[1]+vb[3]):.1f}" width="{vb[2]:.1f}" height="{vb[3]:.1f}" fill="{bg}"/>'
    s+=f'<style>.re{{fill:{re_col}}}.ring{{fill:none;stroke:{ring_col};stroke-width:{STEM:.1f}}}</style><g transform="scale(1 -1)">'
    s+=f'<circle class="ring" cx="{cx:.1f}" cy="{cy:.1f}" r="{r_mid:.1f}"/>'+paths(re_glyphs,0,"re")+'</g></svg>'
    return s
def svg_ring_only(ring_col,bg=None):
    R=r_out+0.35*CAP; vb=(cx-R,cy-R,2*R,2*R)
    s=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb[0]:.1f} {-(vb[1]+vb[3]):.1f} {vb[2]:.1f} {vb[3]:.1f}">'
    if bg: s+=f'<rect x="{vb[0]:.1f}" y="{-(vb[1]+vb[3]):.1f}" width="{vb[2]:.1f}" height="{vb[3]:.1f}" fill="{bg}"/>'
    s+=f'<g transform="scale(1 -1)"><circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r_mid:.1f}" fill="none" stroke="{ring_col}" stroke-width="{STEM:.1f}"/></g></svg>'
    return s
files={
 "lockup-ring-green-on-carbon.svg": svg_lockup(G,P,G,C),
 "lockup-ring-white-on-carbon.svg": svg_lockup(P,P,G,C),
 "lockup-ring-transparent-dark.svg": svg_lockup(G,P,G),
 "lockup-ring-transparent-light.svg": svg_lockup("#14C48E","#131614","#14C48E"),
 "lockup-ring-mono-black.svg": svg_lockup("#000","#000","#000"),
 "mark-ring-re-green-on-carbon.svg": svg_mark(G,G,C),
 "mark-ring-re-white-on-carbon.svg": svg_mark(P,G,C),
 "mark-ring-re-transparent.svg": svg_mark(G,G),
 "mark-ring-only.svg": svg_ring_only(G),
}
for fn,s in files.items(): open(f"{OUT}/{fn}","w").write(s)
meta={"upm":UPM,"capHeight":CAP,"stem":round(STEM,1),"ringCenter":[round(cx,1),round(cy,1)],"ringInner":round(r_in,1),"ringMid":round(r_mid,1),"ringOuter":round(r_out,1),"clearance":CLEAR,"gapToP":GAP,"tracking":TRACK,"wdth":WDTH,"wght":WGHT,"lockupViewBox":[round(left,1),round(bottom,1),round(W,1),round(H,1)],"ringDiameterOverCap":round(2*r_out/CAP,3)}
json.dump(meta,open(f"{OUT}/geometry.json","w"),indent=2)
print(json.dumps(meta))
