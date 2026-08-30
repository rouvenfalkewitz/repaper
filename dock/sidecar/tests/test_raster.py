"""Round-trip tests: encode a known image as PWG / URF with a tiny reference encoder, decode, compare."""
import struct
from PIL import Image, ImageDraw
from repaper_dock.render.raster import decode_pwg, decode_urf
from repaper_dock.render import decode_document


def rle_encode_line(line: bytes, pixel_bytes: int) -> bytes:
    """Reference PWG/URF encoder: runs of identical pixels → (n-1, pixel); literals → (257-n, pixels)."""
    px = [line[i:i + pixel_bytes] for i in range(0, len(line), pixel_bytes)]
    out = bytearray(); i = 0
    while i < len(px):
        j = i
        while j + 1 < len(px) and px[j + 1] == px[i] and j - i < 127: j += 1
        if j > i: out += bytes([j - i]) + px[i]; i = j + 1; continue
        k = i
        while k + 1 < len(px) and px[k + 1] != px[k] and k - i < 127: k += 1
        n = k - i + 1
        if n == 1: out += b"\x00" + px[i]                      # a single pixel is "repeat once"
        else: out += bytes([257 - n]) + b"".join(px[i:k + 1])
        i = k + 1
    return bytes(out)


def sample(w=64, h=40):
    img = Image.new("L", (w, h), 255); d = ImageDraw.Draw(img)
    d.rectangle([4, 4, 30, 20], fill=0); d.line([0, 39, 63, 0], fill=0); d.rectangle([40, 25, 60, 35], fill=128)
    return img


def encode_pwg(img: Image.Image, bpp=8, cspace=18) -> bytes:
    w, h = img.size; bpl = (w * bpp + 7) // 8
    hdr = bytearray(1796)
    struct.pack_into(">II", hdr, 372, w, h)
    struct.pack_into(">IIIIII", hdr, 384, bpp if bpp < 8 else 8, bpp, bpl, 0, cspace, 1)
    body = bytearray()
    raw = img.tobytes() if bpp == 8 else img.convert("1").tobytes()
    if cspace == 3 and bpp == 1: raw = bytes(~b & 0xFF for b in raw)     # K: 1 = black
    for y in range(h):
        body += b"\x00" + rle_encode_line(raw[y * bpl:(y + 1) * bpl], max(1, bpp // 8))
    return b"RaS2" + bytes(hdr) + bytes(body)


def encode_urf(img: Image.Image) -> bytes:
    w, h = img.size; hdr = bytearray(32); hdr[0] = 8; hdr[1] = 0
    struct.pack_into(">III", hdr, 12, w, h, 300)
    raw = img.tobytes(); body = bytearray()
    for y in range(h): body += b"\x00" + rle_encode_line(raw[y * w:(y + 1) * w], 1)
    return b"UNIRAST\0" + struct.pack(">I", 1) + bytes(hdr) + bytes(body)


def test_pwg_gray_roundtrip():
    img = sample(); pages = list(decode_pwg(encode_pwg(img)))
    assert len(pages) == 1 and pages[0].size == img.size and list(pages[0].getdata()) == list(img.getdata())


def test_pwg_1bit_black_roundtrip():
    img = sample().point(lambda v: 255 if v > 127 else 0)
    pages = list(decode_pwg(encode_pwg(img, bpp=1, cspace=3)))
    assert list(pages[0].getdata()) == list(img.getdata())


def test_urf_roundtrip_and_autodetect():
    img = sample(); data = encode_urf(img)
    pages = list(decode_urf(data)); assert list(pages[0].getdata()) == list(img.getdata())
    assert len(decode_document(data)) == 1 and len(decode_document(encode_pwg(img), "image/pwg-raster")) == 1


def test_line_repeat():
    img = Image.new("L", (16, 4), 255); raw = encode_pwg(img)
    # collapse the 4 identical lines into one line with repeat=3
    hdr, body = raw[:1800], raw[1800:]
    one = body[:len(body) // 4]; assert body == one * 4
    collapsed = hdr + bytes([3]) + one[1:]
    assert list(decode_pwg(collapsed))[0].size == (16, 4)
