"""Decoders for the two raster formats driverless clients send: PWG Raster (IPP Everywhere / Windows / Android)
and Apple Raster 'URF' (AirPrint). Both are a header per page plus run-length-encoded lines.

Yields PIL images ('L' or 'RGB') per page. Pure Python; fast enough for label/page sizes."""
from __future__ import annotations
import struct
from typing import Iterator
from PIL import Image

PWG_SYNC = b"RaS2"            # PWG raster: CUPS v2 header, big-endian, compression 1
URF_MAGIC = b"UNIRAST\0"


class RasterError(ValueError):
    pass


def _rle_lines(data: bytes, pos: int, height: int, bytes_per_line: int, pixel_bytes: int, blank: int) -> tuple[list[bytes], int]:
    """Decode `height` lines of PWG/URF RLE starting at `pos`. Returns (lines, new_pos)."""
    lines: list[bytes] = []
    while len(lines) < height:
        if pos >= len(data): raise RasterError("truncated raster data")
        repeat = data[pos] + 1; pos += 1
        line = bytearray(); need = bytes_per_line
        while need > 0:
            if pos >= len(data): raise RasterError("truncated line")
            n = data[pos]; pos += 1
            if n == 128:                                     # URF: fill the rest of the line with blank
                line += bytes([blank]) * need; need = 0
            elif n < 128:                                    # repeat next pixel n+1 times
                px = data[pos:pos + pixel_bytes]; pos += pixel_bytes
                cnt = min(n + 1, need // pixel_bytes); line += px * cnt; need -= cnt * pixel_bytes
            else:                                            # copy 257-n pixels literally
                cnt = min(257 - n, need // pixel_bytes); line += data[pos:pos + cnt * pixel_bytes]
                pos += cnt * pixel_bytes; need -= cnt * pixel_bytes
        lines.extend([bytes(line)] * min(repeat, height - len(lines)))
    return lines, pos


def _to_image(lines: list[bytes], width: int, height: int, bpp: int, colorspace_is_black: bool) -> Image.Image:
    raw = b"".join(lines)
    if bpp == 1:
        img = Image.frombytes("1", (width, height), raw, "raw", "1;I" if colorspace_is_black else "1")
        return img.convert("L")
    if bpp == 8:
        img = Image.frombytes("L", (width, height), raw)
        return img.point(lambda v: 255 - v) if colorspace_is_black else img
    if bpp == 24:
        return Image.frombytes("RGB", (width, height), raw)
    if bpp == 32:
        return Image.frombytes("CMYK", (width, height), raw).convert("RGB")
    raise RasterError(f"unsupported bits per pixel: {bpp}")


def decode_pwg(data: bytes) -> Iterator[Image.Image]:
    if data[:4] != PWG_SYNC: raise RasterError("not PWG raster (RaS2)")
    pos = 4
    while pos + 1796 <= len(data):
        h = data[pos:pos + 1796]; pos += 1796
        width, height = struct.unpack(">II", h[372:380])
        bpc, bpp, bpl, order, cspace, comp = struct.unpack(">IIIIII", h[384:408])
        if comp != 1: raise RasterError("PWG raster must be compressed (cupsCompression=1)")
        pixel_bytes = max(1, bpp // 8)
        # colour spaces: 3 = K (1 = black), 18 = sGray, 19 = sRGB, 6 = CMYK, 0/1 = W/RGB
        is_black = cspace in (3, 6)
        blank = 0x00 if is_black else 0xFF
        lines, pos = _rle_lines(data, pos, height, bpl, pixel_bytes, blank)
        yield _to_image(lines, width, height, bpp, is_black)


def decode_urf(data: bytes) -> Iterator[Image.Image]:
    if data[:8] != URF_MAGIC: raise RasterError("not Apple raster (UNIRAST)")
    (pages,) = struct.unpack(">I", data[8:12]); pos = 12
    for _ in range(pages):
        h = data[pos:pos + 32]; pos += 32
        bpp, cspace, duplex, quality = h[0], h[1], h[2], h[3]
        width, height, dpi = struct.unpack(">III", h[12:24])
        pixel_bytes = max(1, bpp // 8); bpl = (width * bpp + 7) // 8
        lines, pos = _rle_lines(data, pos, height, bpl, pixel_bytes, 0xFF)
        yield _to_image(lines, width, height, bpp, colorspace_is_black=False)   # URF: 0 = black, 255 = white
