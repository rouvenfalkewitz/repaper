"""OpenDisplay over Bluetooth LE (https://opendisplay.org) via the `py-opendisplay` SDK.

Address = the sheet's BLE MAC ("AA:BB:…") or its OpenDisplay name ("OD97D9BE", the lower 3 MAC bytes — the SDK
resolves it by scanning). keys["key"] = AES-128 master key as hex (from the sheet's QR landing URL), if encrypted.
The SDK can dither itself; we pass our already-dithered page with dithering OFF so every transport produces
identical pixels.

Known firmware limitation (SDK warns about it): on current firmware, BWR/BWY *direct write* stores only one plane,
so red/yellow may be dropped on some tags until the firmware gains parity. The pipeline is right; the tag may not be yet.
"""
from __future__ import annotations
import asyncio, base64, re, time
from typing import Optional
from .base import SheetTransport, SheetRef, SheetModel, SheetStatus, Page, PrintResult, TransportError

_SCHEME_TO_PALETTE = {"MONO": "BW", "BWR": "BWR", "BWY": "BWR", "BWRY": "BWRY", "GRAYSCALE_4": "BW", "GRAYSCALE_16": "BW"}
LANDING_PREFIX = "https://opendisplay.org/l/?"


def parse_landing_url(url: str) -> dict:
    """Decode the QR / landing URL a sheet shows: 23 bytes = tag_type u16, device id 3 B, AES key 16 B, manufacturer u16."""
    tok = url.strip().split("?", 1)[-1].strip("/")
    raw = base64.urlsafe_b64decode(tok + "=" * (-len(tok) % 4))
    if len(raw) != 23: raise ValueError("not an OpenDisplay landing URL (payload must be 23 bytes)")
    key = raw[5:21]
    return {"tag_type": int.from_bytes(raw[0:2], "big"), "device_id": raw[2:5].hex().upper(),
            "name": "OD" + raw[2:5].hex().upper(), "key": None if key == b"\0" * 16 else key.hex(),
            "manufacturer_id": int.from_bytes(raw[21:23], "big")}


def _is_mac(s: str) -> bool: return bool(re.fullmatch(r"([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}", s))


def _run(coro):
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)
    raise TransportError("OpenDisplay transport called from inside an event loop; use the async API")


class OpenDisplayBLETransport(SheetTransport):
    def __init__(self, registry=None, timeout: float = 20.0):
        self.registry = registry; self.timeout = timeout

    @property
    def id(self) -> str: return "opendisplay-ble"

    def _key(self, ref: SheetRef) -> Optional[bytes]:
        k = ref.keys.get("key")
        return bytes.fromhex(k.replace(":", "").replace(" ", "")) if k else None

    def _device(self, ref: SheetRef):
        from opendisplay import OpenDisplayDevice
        kw = {"mac_address": ref.address} if _is_mac(ref.address) else {"device_name": ref.address}
        return OpenDisplayDevice(encryption_key=self._key(ref), timeout=self.timeout, discovery_timeout=self.timeout, **kw)

    def discover(self, timeout: float = 5.0) -> list[SheetRef]:
        try:
            from opendisplay import discover_devices
            found = _run(discover_devices(timeout=timeout))          # {name: mac}
        except Exception:                                            # no BLE / no permission: report nothing, don't crash
            return []
        return [SheetRef(self.id, mac, {}, name) for name, mac in found.items()]

    def describe(self, ref: SheetRef) -> SheetModel:
        sid = self.registry.find_by_address(self.id, ref.address) if self.registry else None
        if sid: return self.registry.get(sid)[1]
        return _run(self._describe_async(ref))

    async def _describe_async(self, ref: SheetRef) -> SheetModel:
        async with self._device(ref) as dev:
            caps = dev.capabilities
            scheme = getattr(caps.color_scheme, "name", str(caps.color_scheme))
            return SheetModel(int(caps.width), int(caps.height), _SCHEME_TO_PALETTE.get(scheme, "BW"),
                              rotation=int(getattr(caps, "rotation", 0) or 0), model_name=f"OpenDisplay {scheme}")

    def status(self, ref: SheetRef) -> SheetStatus:
        """Online = seen in a short scan. Battery/temperature from advertisements come in a later revision."""
        try:
            from opendisplay import discover_devices
            found = _run(discover_devices(timeout=4.0))
        except Exception:
            return SheetStatus()
        seen = ref.address.upper() in {v.upper() for v in found.values()} or ref.address.upper() in {k.upper() for k in found}
        return SheetStatus(online=seen, last_seen=time.time() if seen else None)

    def print(self, ref: SheetRef, page: Page, *, full_refresh: bool = True) -> PrintResult:
        t0 = time.time()
        try:
            _run(self._print_async(ref, page, full_refresh))
        except Exception as e:
            return PrintResult(False, time.time() - t0, f"{type(e).__name__}: {e}")
        return PrintResult(True, time.time() - t0, "sent over BLE")

    async def _print_async(self, ref: SheetRef, page: Page, full_refresh: bool):
        from opendisplay import DitherMode, FitMode, RefreshMode, Rotation
        img = page.image.convert("RGB")
        async with self._device(ref) as dev:
            w, h = int(dev.width), int(dev.height)
            if (w, h) != img.size and (h, w) != img.size:
                raise TransportError(f"sheet is {w}×{h}, page is {img.width}×{img.height} — registry model is wrong")
            rot = Rotation.ROTATE_0 if (w, h) == img.size else Rotation.ROTATE_90
            await dev.upload_image(img, refresh_mode=RefreshMode.FULL if full_refresh else RefreshMode.FAST,
                                   dither_mode=DitherMode.NONE, fit=FitMode.STRETCH, rotate=rot)

    def capabilities(self):
        return {"supports_status": True, "supports_partial_refresh": True, "needs_pairing": True}
