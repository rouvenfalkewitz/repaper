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
