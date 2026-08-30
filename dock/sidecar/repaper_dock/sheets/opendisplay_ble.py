"""OpenDisplay over Bluetooth LE (https://opendisplay.org) via the `py-opendisplay` SDK.

Address = the sheet's BLE MAC ("AA:BB:…") or its OpenDisplay name ("OD97D9BE", the lower 3 MAC bytes — the SDK
resolves it by scanning). keys["key"] = AES-128 master key as hex (from the sheet's QR landing URL), if encrypted.
The SDK can dither itself; we pass our already-dithered page with dithering OFF so every transport produces
identical pixels.

Firmware notes: the nRF52811/SoluM firmware (Firmware_NRF) writes both BWR planes correctly and does NOT support
compressed uploads — so the SDK must interrogate the device (read its config) before every upload; bypassing that
with explicit capabilities makes the SDK compress, and the tag then draws zlib bytes as pixels. The ESP32/nRF52840
firmware (FINDINGS C1) keeps only one plane on BWR; `bwr_as_mono=True` is the opt-in workaround for those boards.
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
    def __init__(self, registry=None, timeout: float = 30.0, bwr_as_mono: bool = False, refresh_wait: float = 60.0, min_battery_mv: int = 2700):
        self.registry = registry; self.timeout = timeout; self.bwr_as_mono = bwr_as_mono; self.refresh_wait = refresh_wait
        self.min_battery_mv = min_battery_mv

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
        """The SDK reports the panel's native size plus the mounting rotation it applies itself; we render in the
        *viewed* orientation (native size swapped for 90°/270°) and let the SDK rotate — so rotation is 0 for us.
        The native facts are remembered in ref.keys so later prints need no interrogation round-trip."""
        async with self._device(ref) as dev:
            caps = dev.capabilities
            scheme = getattr(caps.color_scheme, "name", str(caps.color_scheme))
            w, h, rot = int(caps.width), int(caps.height), int(getattr(caps, "rotation", 0) or 0)
            ref.keys.update({"native": f"{caps.width}x{caps.height}", "rotation": rot, "scheme": scheme})
            if rot in (90, 270): w, h = h, w
            return SheetModel(w, h, _SCHEME_TO_PALETTE.get(scheme, "BW"), rotation=0,
                              model_name=f"OpenDisplay {scheme} {caps.width}x{caps.height} rot{rot}")

    def _known_caps(self, ref: SheetRef, mono: bool):
        """Explicit DeviceCapabilities from the registry (skips interrogation); None if we never described this sheet."""
        if not ref.keys.get("native"): return None
        from opendisplay import DeviceCapabilities, ColorScheme
        nw, nh = (int(v) for v in ref.keys["native"].lower().split("x"))
        scheme = ColorScheme.MONO if mono else getattr(ColorScheme, ref.keys.get("scheme", "MONO"), ColorScheme.MONO)
        return DeviceCapabilities(width=nw, height=nh, color_scheme=scheme, rotation=int(ref.keys.get("rotation", 0)))

    def status(self, ref: SheetRef, timeout: float = 6.0) -> SheetStatus:
        """From the sheet's BLE advertisement (no connection): battery (10 mV steps), temperature (0.5 °C), reboot flag.
        Manufacturer data 0x2446, v1 layout: [..11]=temp (t+40)*2, [12]=mV/10 low byte, [13]=bit0 mV MSB, bit1 reboot."""
        try:
            adv = _run(self._scan_for(ref, timeout))
        except Exception:
            return SheetStatus()
        if adv is None: return SheetStatus(online=False)
        md = adv
        mv = (md[12] | ((md[13] & 1) << 8)) * 10 if len(md) >= 14 else None
        temp = md[11] / 2 - 40 if len(md) >= 12 else None
        pct = None if mv is None else max(0.0, min(100.0, (mv - 2400) / (3000 - 2400) * 100))   # rough, lithium primary
        return SheetStatus(battery_percent=pct, battery_volts=None if mv is None else mv / 1000, temperature_c=temp,
                           online=True, last_seen=time.time())

    async def _scan_for(self, ref: SheetRef, timeout: float):
        from bleak import BleakScanner
        want = ref.address.upper(); hit = {}
        def cb(d, adv):
            md = adv.manufacturer_data.get(0x2446)
            if md and (want in ((d.name or adv.local_name or "").upper(), d.address.upper())): hit["md"] = bytes(md)
        s = BleakScanner(detection_callback=cb); await s.start()
        t0 = time.time()
        while "md" not in hit and time.time() - t0 < timeout: await asyncio.sleep(0.2)
        await s.stop(); return hit.get("md")

    def print(self, ref: SheetRef, page: Page, *, full_refresh: bool = True) -> PrintResult:
        """Some tags (seen: SoluM nRF52811, firmware 1.0.0) acknowledge every chunk and the END, report 'refresh
        started', then drop the BLE link while the panel refreshes and never send REFRESH_COMPLETE. The image is on
        the panel at that point, so that outcome counts as printed — with a note, not a failure."""
        import logging
        t0 = time.time(); seen = {"refresh_started": False}
        # A refresh on a weak cell browns the tag out mid-refresh (seen: 2.67 V → half-cleared panel, reset, 2.25 V after).
        st = self.status(ref, timeout=4.0)
        if st.battery_volts is not None and st.battery_volts * 1000 < self.min_battery_mv:
            return PrintResult(False, time.time() - t0, f"sheet battery too low to refresh safely ({st.battery_volts:.2f} V < {self.min_battery_mv/1000:.2f} V) — replace the cell")

        class _Watch(logging.Handler):
            def emit(self, rec):
                if rec.getMessage().startswith("Display refresh started"): seen["refresh_started"] = True
        watch = _Watch(level=logging.DEBUG); lg = logging.getLogger("opendisplay.device"); prev = lg.level
        lg.addHandler(watch); lg.setLevel(logging.DEBUG)
        try:
            _run(self._print_async(ref, page, full_refresh))
        except Exception as e:
            if seen["refresh_started"] and type(e).__name__ in ("BLETimeoutError", "BLEConnectionError"):
                return PrintResult(True, time.time() - t0, "sent over BLE; panel refresh started, completion not confirmed (tag dropped the link while refreshing)")
            return PrintResult(False, time.time() - t0, f"{type(e).__name__}: {e}")
        finally:
            lg.removeHandler(watch); lg.setLevel(prev)
        return PrintResult(True, time.time() - t0, "sent over BLE, refresh complete")

    async def _print_async(self, ref: SheetRef, page: Page, full_refresh: bool):
        from opendisplay import DitherMode, FitMode, RefreshMode, Rotation
        img = page.image.convert("RGB")
        # Opt-in workaround for boards whose firmware keeps only one plane on BWR (ESP32/nRF52840, FINDINGS C1):
        # send as MONO with red drawn black. Off by default — the nRF52811/SoluM firmware writes both planes.
        mono = self.bwr_as_mono and ref.keys.get("scheme") in ("BWR", "BWY")
        if mono and page.model.palette in ("BWR", "BWRY"):
            lut = page.image.point(lambda i: 0 if i == 0 else 1, "P")            # index 0 = white, everything else = black
            lut.putpalette([255, 255, 255, 0, 0, 0] + [0] * 762); img = lut.convert("RGB")
        # Always let the SDK interrogate the device: its config decides compression and encoding. Never pass
        # explicit capabilities for a normal print — that skips the config and the SDK may compress for a tag that can't.
        async with self._device(ref) as dev:
            dev.TIMEOUT_REFRESH = self.refresh_wait
            c = dev.capabilities; w, h = int(c.width), int(c.height)
            if int(getattr(c, "rotation", 0) or 0) in (90, 270): w, h = h, w            # viewed orientation
            if (w, h) != img.size:
                raise TransportError(f"sheet shows {w}×{h}, page is {img.width}×{img.height} — re-register the sheet")
            await dev.upload_image(img, refresh_mode=RefreshMode.FULL if full_refresh else RefreshMode.FAST,
                                   dither_mode=DitherMode.NONE, fit=FitMode.STRETCH, rotate=Rotation.ROTATE_0,
                                   compress=bool(ref.keys.get("compress", True)))

    def capabilities(self):
        return {"supports_status": True, "supports_partial_refresh": True, "needs_pairing": True, "min_battery_mv": self.min_battery_mv}
