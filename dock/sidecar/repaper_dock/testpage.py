"""A built-in test page: the wordmark-ish header, a ruler and a text block, sized for the sheet."""
from __future__ import annotations
from PIL import Image, ImageDraw
from .sheets.base import SheetModel


def test_page(model: SheetModel) -> Image.Image:
    W, H = model.width, model.height
    img = Image.new("RGB", (W, H), "white"); d = ImageDraw.Draw(img)
    m = max(4, W // 40)
    d.rectangle([m, m, W - m - 1, H - m - 1], outline="black", width=max(1, W // 150))
    d.text((m * 2, m * 2), "REPAPER", fill="black")
    d.text((m * 2, m * 2 + 14), f"{W}x{H} {model.palette}", fill="black")
    for x in range(0, W, 10): d.line([x, H - m - 1, x, H - m - (12 if x % 50 == 0 else 6)], fill="black")
    if model.palette != "BW": d.rectangle([W - m - W // 4, m * 2, W - m * 2, m * 2 + H // 6], fill="red")
    for i in range(6):
        x0 = m * 2 + i * (W - m * 4) // 6; d.rectangle([x0, H // 2, x0 + (W - m * 4) // 6 - 2, H // 2 + H // 8], fill=(i * 51, i * 51, i * 51))
    return img


def calibration_page(model: SheetModel) -> Image.Image:
    """Full-bleed calibration: a 1-px frame at the very edge, 2-px frame 4 px in, and ticks numbered every 10 px
    from each edge, so hidden pixels per edge can be read off the glass. Ignores the sheet's inset on purpose."""
    W, H = model.width, model.height
    img = Image.new("RGB", (W, H), "white"); d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W - 1, H - 1], outline="black")
    d.rectangle([4, 4, W - 5, H - 5], outline="black", width=2)
    for x in range(10, W, 10):                       # ticks along top & bottom, longer every 50
        ln = 12 if x % 50 == 0 else 6
        d.line([x, 0, x, ln], fill="black"); d.line([x, H - 1, x, H - 1 - ln], fill="black")
        if x % 50 == 0: d.text((x + 2, 12), str(x), fill="black"); d.text((x + 2, H - 22), str(x), fill="black")
    for y in range(10, H, 10):                       # ticks along left & right
        ln = 12 if y % 50 == 0 else 6
        d.line([0, y, ln, y], fill="black"); d.line([W - 1, y, W - 1 - ln, y], fill="black")
        if y % 50 == 0: d.text((14, y - 4), str(y), fill="black"); d.text((W - 34, y - 4), str(y), fill="black")
    d.text((W // 2 - 30, H // 2 - 5), f"{W}x{H}", fill="black")
    return img
