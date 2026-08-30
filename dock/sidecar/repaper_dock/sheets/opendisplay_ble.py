"""OpenDisplay over Bluetooth LE (https://opendisplay.org) via the `py-opendisplay` SDK.

Address = the sheet's BLE MAC. keys["key"] = AES-128 master key as hex (from the sheet's QR page), if the sheet is
encrypted. The SDK can dither itself; we pass our already-dithered page with dithering OFF so every transport
produces identical pixels.
"""
from __future__ import annotations
import asyncio, time
from typing import Optional
from .base import SheetTransport, SheetRef, SheetModel, SheetStatus, Page, PrintResult, TransportError

_SCHEME_TO_PALETTE = {"MONO": "BW", "BWR": "BWR", "BWY": "BWR", "BWRY": "BWRY"}   # BWY: treat yellow as the 3rd ink slot


def _run(coro):
    try:
        loop = asyncio.get_running_loop()
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

    def discover(self, timeout: float = 5.0) -> list[SheetRef]:
        try:
            from opendisplay import discover_devices
            found = _run(discover_devices(timeout=timeout))          # {name: mac}
        except Exception as e:                                       # no BLE, no bleak, no permission: report nothing, don't crash
            return []
        return [SheetRef(self.id, mac, {}, name) for name, mac in found.items()]

    def describe(self, ref: SheetRef) -> SheetModel:
        # The registry entry is authoritative if present (it was filled from the device once); otherwise ask the device.
        sid = self.registry.find_by_address(self.id, ref.address) if self.registry else None
        if sid: return self.registry.get(sid)[1]
        return _run(self._describe_async(ref))

    async def _describe_async(self, ref: SheetRef) -> SheetModel:
        from opendisplay import OpenDisplayDevice
        async with OpenDisplayDevice(mac_address=ref.address, encryption_key=self._key(ref), timeout=self.timeout) as dev:
            scheme = getattr(getattr(dev.config, "display", None), "color_scheme_enum", None)
            name = getattr(scheme, "name", "MONO")
            return SheetModel(int(dev.width), int(dev.height), _SCHEME_TO_PALETTE.get(name, "BW"), model_name=f"OpenDisplay {name}")

    def status(self, ref: SheetRef) -> SheetStatus:
        # Battery/temperature come from BLE advertisements; wired in a later revision (SDK: parse_advertisement).
        return SheetStatus()

    def print(self, ref: SheetRef, page: Page, *, full_refresh: bool = True) -> PrintResult:
        t0 = time.time()
        try:
            _run(self._print_async(ref, page, full_refresh))
        except Exception as e:
            return PrintResult(False, time.time() - t0, f"{type(e).__name__}: {e}")
        return PrintResult(True, time.time() - t0, "sent over BLE")

    async def _print_async(self, ref: SheetRef, page: Page, full_refresh: bool):
        from opendisplay import OpenDisplayDevice, DitherMode, FitMode, RefreshMode, Rotation
        img = page.image.convert("RGB")
        async with OpenDisplayDevice(mac_address=ref.address, encryption_key=self._key(ref), timeout=self.timeout) as dev:
            if (int(dev.width), int(dev.height)) != img.size and (int(dev.height), int(dev.width)) != img.size:
                raise TransportError(f"sheet is {dev.width}×{dev.height}, page is {img.width}×{img.height} — registry model is wrong")
            rot = Rotation.ROTATE_0 if (int(dev.width), int(dev.height)) == img.size else Rotation.ROTATE_90
            await dev.upload_image(img, refresh_mode=RefreshMode.FULL if full_refresh else RefreshMode.FAST,
                                   dither_mode=DitherMode.NONE, fit=FitMode.STRETCH, rotate=rot)

    def capabilities(self):
        return {"supports_status": False, "supports_partial_refresh": True, "needs_pairing": True}
