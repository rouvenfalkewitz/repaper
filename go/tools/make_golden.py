#!/usr/bin/env python3
"""Generate golden fixtures for the Kotlin core from py-opendisplay — the same SDK the Dock uses.
Run with the Dock's venv:  dock/sidecar/.venv/bin/python go/tools/make_golden.py"""
import base64, json, struct, sys
from pathlib import Path

from PIL import Image
from epaper_dithering import ColorScheme
from opendisplay import crypto
from opendisplay.encoding.bitplanes import encode_bitplanes
from opendisplay.encoding.images import encode_1bpp, encode_2bpp
from opendisplay.protocol import commands
from opendisplay.protocol.config_parser import parse_config_response

OUT = Path(__file__).resolve().parent.parent / "core/src/test/resources/golden"
OUT.mkdir(parents=True, exist_ok=True)
hx = lambda b: b.hex()

# ── crypto vectors ───────────────────────────────────────────────────────────
master = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
cn = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
sn = bytes.fromhex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf")
dev_id = bytes.fromhex("11223344")
sk = crypto.derive_session_key(master, cn, sn, dev_id)
sid = crypto.derive_session_id(sk, cn, sn)
frame = crypto.encrypt_command(sk, sid, 7, b"\x00\x71", b"\xde\xad\xbe\xef" * 5)
resp_frame = crypto.encrypt_command(sk, sid, 42, b"\x00\x40", b"\x01\x02\x03")
(OUT / "crypto.json").write_text(json.dumps({
    "master": hx(master), "client_nonce": hx(cn), "server_nonce": hx(sn), "device_id": hx(dev_id),
    "session_key": hx(sk), "session_id": hx(sid),
    "challenge_response": hx(crypto.compute_challenge_response(master, sn, cn, dev_id)),
    "server_proof": hx(crypto.compute_server_proof(sk, sn, cn, dev_id)),
    "frame_counter": 7, "frame_cmd": "0071", "frame_payload": hx(b"\xde\xad\xbe\xef" * 5), "frame": hx(frame),
    "resp_frame": hx(resp_frame), "resp_cmd": 0x0040, "resp_payload": hx(b"\x01\x02\x03"),
}, indent=1))

# ── command frames ───────────────────────────────────────────────────────────
(OUT / "commands.json").write_text(json.dumps({
    "read_config": hx(commands.build_read_config_command()),
    "read_fw": hx(commands.build_read_fw_version_command()),
    "auth1": hx(commands.build_authenticate_step1()),
    "auth2": hx(commands.build_authenticate_step2(cn, crypto.compute_challenge_response(master, sn, cn, dev_id))),
    "dw_start": hx(commands.build_direct_write_start_uncompressed()),
    "dw_data": hx(commands.build_direct_write_data_command(b"\x01\x02\x03\x04")),
    "dw_end_full": hx(commands.build_direct_write_end_command(0)),
    "dw_end_fast": hx(commands.build_direct_write_end_command(1)),
}, indent=1))

# ── encodings: logical color grids → SDK bytes ───────────────────────────────
# Grid uses letters W/B/R/Y; per scheme we build the P image in the SDK's own palette
# index order and run the SDK encoder — Kotlin encodes from RePaper order and must match.
GRID = ["WWBBRRWYWB", "BWBWBWBWBW", "RRRRWWWWBB", "YWYWYWBWRW", "WWWWWWWWWW", "BBBBBBBBBB", "WBRYWBRYWB"]
W, H = len(GRID[0]), len(GRID)

def sdk_indexes(scheme):
    """logical letter -> SDK palette index for this scheme."""
    if scheme == ColorScheme.MONO:
        return {"W": 1, "B": 0, "R": 0, "Y": 0}
    if scheme == ColorScheme.BWR:
        return {"W": 1, "B": 0, "R": 2, "Y": 2}
    if scheme == ColorScheme.BWY:
        return {"W": 1, "B": 0, "R": 2, "Y": 2}
    if scheme == ColorScheme.BWRY:
        # order from the dither palette itself, matched by RGB
        colors = list(scheme.palette.colors.items())
        by_rgb = {rgb: i for i, (_, rgb) in enumerate(colors)}
        return {"W": by_rgb[(255, 255, 255)], "B": by_rgb[(0, 0, 0)],
                "R": by_rgb[(255, 0, 0)], "Y": by_rgb[(255, 255, 0)]}
    raise ValueError(scheme)

def p_image(scheme):
    m = sdk_indexes(scheme)
    img = Image.new("P", (W, H))
    img.putdata([m[c] for row in GRID for c in row])
    return img

enc = {"grid": GRID}
enc["mono"] = hx(encode_1bpp(p_image(ColorScheme.MONO)))
p1, p2 = encode_bitplanes(p_image(ColorScheme.BWR), ColorScheme.BWR)
enc["bwr"] = hx(p1 + p2)
p1, p2 = encode_bitplanes(p_image(ColorScheme.BWY), ColorScheme.BWY)
enc["bwy"] = hx(p1 + p2)
enc["bwry"] = hx(encode_2bpp(p_image(ColorScheme.BWRY)))
enc["bwry_logical_to_code"] = sdk_indexes(ColorScheme.BWRY)

# ── the full scheme family, straight from the SDK's own encoders ─────────────
from opendisplay.encoding.images import encode_4bpp
from opendisplay.encoding.bitplanes import encode_gray4_bitplanes
from opendisplay.display_palettes import get_bwry_codes, get_gray4_codes
from PIL import Image as PILImage

# extended grid with green (G), blue (U), orange (O) and grays (0-3 for 4-gray levels)
XGRID = ["WBRYGU", "GUWBRY", "OWBRYG", "WWWWWW", "BBBBBB", "RYGUOW"]
XW, XH = len(XGRID[0]), len(XGRID)

def x_image(letter_to_idx):
    img = PILImage.new("P", (XW, XH))
    img.putdata([letter_to_idx.get(c, 0) for row in XGRID for c in row])
    return img

# SDK palette index orders (from epaper_dithering): BWGBRY/SPLIT: b,w,y,r,blue,green; SEVEN adds orange
bwgbry_idx = {"B": 0, "W": 1, "Y": 2, "R": 3, "U": 4, "G": 5, "O": 3}
seven_idx = {"B": 0, "W": 1, "Y": 2, "R": 3, "U": 4, "G": 5, "O": 6}
enc["xgrid"] = XGRID
enc["bwgbry"] = hx(encode_4bpp(x_image(bwgbry_idx), bwgbry_mapping=True))
enc["bwgbry_split"] = hx(encode_4bpp(x_image(bwgbry_idx), bwgbry_mapping=True, half_planes=True))
enc["seven"] = hx(encode_4bpp(x_image(seven_idx)))

GRAYGRID = ["0123", "3210", "0000", "3333", "1122"]
def g_image():
    img = PILImage.new("P", (4, 5))
    img.putdata([int(c) for row in GRAYGRID for c in row])
    return img
enc["graygrid"] = GRAYGRID
for name, panel in (("gray4_base", 0x0008), ("gray4_v2", 0x0028)):
    p0, p1_ = encode_gray4_bitplanes(g_image(), get_gray4_codes(panel))
    enc[name] = hx(p0 + p1_)
enc["gray4_codes_base"] = list(get_gray4_codes(0x0008))
enc["gray4_codes_v2"] = list(get_gray4_codes(0x0028))

G16GRID = [[0, 5, 10, 15], [15, 10, 5, 0], [1, 2, 3, 4]]
img16 = PILImage.new("P", (4, 3)); img16.putdata([v for row in G16GRID for v in row])
enc["gray16_grid"] = G16GRID
enc["gray16"] = hx(encode_4bpp(img16))

# per-panel BWRY wire codes (0x001D/0x001E swap yellow/red)
enc["bwry_codes_default"] = list(get_bwry_codes(0x0001))
enc["bwry_codes_swapped"] = list(get_bwry_codes(0x001D))
enc["bwry_swapped"] = hx(encode_2bpp(p_image(ColorScheme.BWRY), codes=tuple(get_bwry_codes(0x001D))))
(OUT / "encoding.json").write_text(json.dumps(enc, indent=1))

# ── landing URLs ─────────────────────────────────────────────────────────────
def landing(tag_type, did, key, mfr):
    raw = struct.pack(">H", tag_type) + did + key + struct.pack(">H", mfr)
    assert len(raw) == 23
    return "https://opendisplay.org/l/?" + base64.urlsafe_b64encode(raw).decode().rstrip("=")

key = bytes.fromhex("97d9be0011223344556677889900aabb")
(OUT / "landing.json").write_text(json.dumps({
    "with_key": {"url": landing(3, bytes.fromhex("97d9be"), key, 0x1234), "tag_type": 3,
                 "device_id": "97D9BE", "name": "OD97D9BE", "key": key.hex(), "manufacturer": 0x1234},
    "no_key": {"url": landing(1, bytes.fromhex("abcdef"), b"\0" * 16, 7), "tag_type": 1,
               "device_id": "ABCDEF", "name": "ODABCDEF", "key": None, "manufacturer": 7},
}, indent=1))

# ── a synthetic TLV config, validated against the SDK parser ─────────────────
def packet(num, ptype, body):
    return bytes([num, ptype]) + body

system = struct.pack("<HBBB", 0x0004, 0x01, 0, 0) + b"\0" * 15 + b"\0\0"
manuf = struct.pack("<HH", 0x1001, 0) + b"\0" * 12 + b"\0" * 6
power = bytes([1]) + (2400).to_bytes(3, "little") + struct.pack("<HBBBBBBHIH", 5000, 4, 0, 2, 3, 0, 1, 1000, 5, 60) + struct.pack("<BBBHB", 0, 0, 0, 10, 0) + b"\0" * 4
display = struct.pack("<BBHHHHHHBBBBBBBBBB", 0, 1, 0x0001, 250, 122, 48, 23, 3, 1, 1, 2, 3, 4, 5, 1, 1, 0x01, 6) + b"\0" * 7 + (1500).to_bytes(2, "little") + b"\0" * 13
security = struct.pack("<H", 300) + b"\0" * 62
packets = packet(0, 0x01, system) + packet(1, 0x02, manuf) + packet(2, 0x04, power) + packet(3, 0x20, display) + packet(4, 0x27, security)
tlv = struct.pack("<H", len(packets)) + bytes([1]) + packets + b"\0\0"
cfg = parse_config_response(tlv)
d = cfg.displays[0]
assert (d.pixel_width, d.pixel_height, d.color_scheme, d.rotation) == (250, 122, 1, 1), (d.pixel_width, d.pixel_height, d.color_scheme, d.rotation)
(OUT / "config.json").write_text(json.dumps({
    "tlv": hx(tlv), "width": 250, "height": 122, "scheme": "BWR", "rotation_degrees": 90,
    "session_timeout": cfg.security_config.session_timeout_seconds if cfg.security_config else 0,
}, indent=1))

print("golden fixtures written to", OUT)
for f in sorted(OUT.glob("*.json")):
    print(" ", f.name, f.stat().st_size, "bytes")
