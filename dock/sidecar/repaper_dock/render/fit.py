"""Page image → Page for a specific sheet: rotate to fit, scale (contain, white background), dither to the palette."""
from __future__ import annotations
from PIL import Image, ImageOps
from ..sheets.base import SheetModel, Page, PALETTES


def _palette_image(palette) -> Image.Image:
    p = Image.new("P", (1, 1))
    flat = [c for rgb in palette.colors for c in rgb]
    p.putpalette(flat + [0] * (768 - len(flat)))
    return p


def render_for_sheet(src: Image.Image, model: SheetModel, *, dither: bool = True, auto_rotate: bool = True,
                     trim: bool = True) -> Page:
    img = src.convert("RGB")
    if trim:                                             # drop the white margins clients add around a page
        bbox = ImageOps.invert(img.convert("L")).point(lambda v: 255 if v > 16 else 0).getbbox()
        if bbox: img = img.crop(bbox)
    W, H = model.width, model.height
    if auto_rotate and ((img.width > img.height) != (W > H)):
        img = img.rotate(90, expand=True)
    scale = min(W / img.width, H / img.height)
    size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
    img = img.resize(size, Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), (255, 255, 255))
    canvas.paste(img, ((W - size[0]) // 2, (H - size[1]) // 2))
    pal = _palette_image(model.colors)
    out = canvas.quantize(palette=pal, dither=Image.Dither.FLOYDSTEINBERG if dither else Image.Dither.NONE)
    out.putpalette(pal.getpalette())
    return Page(out, model)
