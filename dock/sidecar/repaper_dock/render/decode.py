"""Turn whatever the printer received into a list of page images (PIL 'L'/'RGB')."""
from __future__ import annotations
import io
from PIL import Image
from .raster import decode_pwg, decode_urf, PWG_SYNC, URF_MAGIC


def decode_document(data: bytes, content_type: str | None = None) -> list[Image.Image]:
    ct = (content_type or "").lower()
    if data[:4] == PWG_SYNC or ct == "image/pwg-raster":
        return list(decode_pwg(data))
    if data[:8] == URF_MAGIC or ct == "image/urf":
        return list(decode_urf(data))
    if ct in ("image/jpeg", "image/png", "") or data[:2] == b"\xff\xd8" or data[:8] == b"\x89PNG\r\n\x1a\n":
        img = Image.open(io.BytesIO(data)); img.load()
        return [img.convert("RGB") if img.mode not in ("L", "RGB") else img]
    raise ValueError(f"unsupported document format: {content_type!r}")
