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
    def __init__(self, registry=None, timeout: float = 20.0, scan_timeout: float = 12.0, bwr_as_mono: bool = False,
                 refresh_wait: float = 60.0, min_battery_mv: int = 2700):
        self.registry = registry; self.timeout = timeout; self.scan_timeout = scan_timeout; self.bwr_as_mono = bwr_as_mono
        self.refresh_wait = refresh_wait; self.min_battery_mv = min_battery_mv

    @property
    def id(self) -> str: return "opendisplay-ble"

    def _key(self, ref: SheetRef) -> Optional[bytes]:
        k = ref.keys.get("key")
        return bytes.fromhex(k.replace(":", "").replace(" ", "")) if k else None

    def _device(self, ref: SheetRef, use_cache: bool = True):
        """Address by BLE address when known (instant), else by OD name (needs a scan, up to `scan_timeout`).
        The address the SDK resolves is cached in ref.keys['ble_address'] (a CoreBluetooth UUID on macOS, a MAC on Linux)."""
        from opendisplay import OpenDisplayDevice
        cached = ref.keys.get("ble_address") if use_cache else None
        if _is_mac(ref.address): kw = {"mac_address": ref.address}
        elif cached: kw = {"mac_address": cached}
        else: kw = {"device_name": ref.address}
        return OpenDisplayDevice(encryption_key=self._key(ref), timeout=self.timeout, discovery_timeout=self.scan_timeout, **kw)

    def _remember_address(self, ref: SheetRef, dev) -> None:
        addr = getattr(dev, "mac_address", None) or getattr(dev, "_mac_address", None)
        if addr and ref.keys.get("ble_address") != addr:
            ref.keys["ble_address"] = addr
            sid = self.registry.find_by_address(self.id, ref.address) if self.registry else None
            if sid: self.registry.update_keys(sid, ble_address=addr)

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
            self._remember_address(ref, dev)
            caps = dev.capabilities
            scheme = getattr(caps.color_scheme, "name", str(caps.color_scheme))
            w, h, rot = int(caps.width), int(caps.height), int(getattr(caps, "rotation", 0) or 0)
            ref.keys.update({"native": f"{caps.width}x{caps.height}", "rotation": rot, "scheme": scheme})
            ref.keys["hw"] = await self._hardware_async(dev)
            sid = self.registry.find_by_address(self.id, ref.address) if self.registry else None
            if sid: self.registry.update_keys(sid, **{k: ref.keys[k] for k in ("native", "rotation", "scheme", "hw")})
            if rot in (90, 270): w, h = h, w
            return SheetModel(w, h, _SCHEME_TO_PALETTE.get(scheme, "BW"), rotation=0,
                              model_name=f"OpenDisplay {scheme} {caps.width}x{caps.height} rot{rot}")

    async def _hardware_async(self, dev) -> dict:
        """What the sheet is, physically — read once from the device config (shown on the Dock's sheet card)."""
        hw = {}
        try:
            fw = await dev.read_firmware_version()          # a dict: major, minor, patch, sha
            get = (lambda k: fw.get(k)) if isinstance(fw, dict) else (lambda k: getattr(fw, k, None))
            parts = [get(k) for k in ("major", "minor", "patch")]
            hw["firmware"] = ".".join(str(v) for v in parts if v is not None) if parts[0] is not None else str(fw)
            sha = str(get("sha") or "").strip('"')
            if sha and set(sha) != {sha[0]} and not sha.startswith("1234567890"): hw["firmware"] += f" ({sha[:7]})"   # placeholder SHAs are not worth showing
        except Exception: pass
        try:
            from opendisplay import ICType
            cfg = dev.config
            ic = getattr(cfg.system, "ic_type", None)
            try: hw["mcu"] = ICType(ic).name.replace("_", " ") if ic is not None else None
            except Exception: hw["mcu"] = f"0x{ic:04x}" if ic is not None else None
            mfr = getattr(cfg.manufacturer, "manufacturer_name", None) or ""
            board = dev.get_board_type_name() if hasattr(dev, "get_board_type_name") else None
            rev = getattr(cfg.manufacturer, "board_revision", None)
            hw["board"] = " / ".join(x for x in (mfr, board) if x) + (f" rev {rev}" if rev else "")
            d = cfg.displays[0] if cfg.displays else None
            if d is not None:
                pt = getattr(d, "panel_ic_type", None)          # OpenDisplay panel type code; the SDK has no name table for it
                if pt: hw["panel"] = f"type 0x{pt:04x}"
                if getattr(d, "active_width_mm", None) and getattr(d, "active_height_mm", None):
                    hw["mm"] = f"{d.active_width_mm}×{d.active_height_mm} mm"
                if getattr(d, "screen_diagonal_inches", None): hw["inch"] = round(float(d.screen_diagonal_inches), 1)
            mah = getattr(cfg.power, "battery_mah", None)
            if mah: hw["battery_mah"] = int(mah)
        except Exception: pass
        return {k: v for k, v in hw.items() if v not in (None, "")}

    def hardware(self, ref: SheetRef) -> dict:
        """Read hardware facts now (BLE) and store them in the registry."""
        async def go():
            async with self._device(ref) as dev:
                self._remember_address(ref, dev); return await self._hardware_async(dev)
        hw = _run(go()); ref.keys["hw"] = hw
        sid = self.registry.find_by_address(self.id, ref.address) if self.registry else None
        if sid: self.registry.update_keys(sid, hw=hw)
        return hw

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

    _PHASES = (("Resolving device name", "looking for the sheet"), ("Connecting to", "connecting"),
               ("Authentication successful", "connected — sending"), ("Display refresh started", "sheet is refreshing (about 20 s)"),
               ("Display refresh complete", "printed"))

    def print(self, ref: SheetRef, page: Page, *, full_refresh: bool = True, progress=None) -> PrintResult:
        """Phases are read off the SDK's own log lines so the UI can narrate connect → send → refresh.
        A tag on a weak cell can drop the link mid-refresh; 'refresh started' followed by a link drop still counts
        as printed (the data was on the panel), with a note."""
        import logging
        t0 = time.time(); seen = {"refresh_started": False}

        class _Watch(logging.Handler):
            def emit(self, rec):
                msg = rec.getMessage()
                if msg.startswith("Display refresh started"): seen["refresh_started"] = True
                if progress:
                    for needle, text in OpenDisplayBLETransport._PHASES:
                        if msg.startswith(needle): progress(text); break
        watch = _Watch(level=logging.DEBUG); lgs = [logging.getLogger(n) for n in ("opendisplay.device", "opendisplay.transport.connection")]
        prev = [lg.level for lg in lgs]
        for lg in lgs: lg.addHandler(watch); lg.setLevel(logging.DEBUG)
        st = self.status(ref, timeout=4.0)
        if st.battery_volts is not None and st.battery_volts * 1000 < self.min_battery_mv:
            for lg, lv in zip(lgs, prev): lg.removeHandler(watch); lg.setLevel(lv)
            return PrintResult(False, time.time() - t0, f"sheet battery too low to refresh safely ({st.battery_volts:.2f} V < {self.min_battery_mv/1000:.2f} V) — replace the cell")
        try:
            _run(self._print_async(ref, page, full_refresh))
        except Exception as e:
            if seen["refresh_started"] and type(e).__name__ in ("BLETimeoutError", "BLEConnectionError"):
                return PrintResult(True, time.time() - t0, "sent over BLE; panel refresh started, completion not confirmed (tag dropped the link while refreshing)")
            return PrintResult(False, time.time() - t0, f"{type(e).__name__}: {e}")
        finally:
            for lg, lv in zip(lgs, prev): lg.removeHandler(watch); lg.setLevel(lv)
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
        try:
            dev_cm = self._device(ref); dev = await dev_cm.__aenter__()
        except Exception:
            if not ref.keys.get("ble_address"): raise
            dev_cm = self._device(ref, use_cache=False); dev = await dev_cm.__aenter__()   # cached address stale → scan by name
        try:
            self._remember_address(ref, dev)
            dev.TIMEOUT_REFRESH = self.refresh_wait
            c = dev.capabilities; w, h = int(c.width), int(c.height)
            if int(getattr(c, "rotation", 0) or 0) in (90, 270): w, h = h, w            # viewed orientation
            if (w, h) != img.size:
                raise TransportError(f"sheet shows {w}×{h}, page is {img.width}×{img.height} — re-register the sheet")
            await dev.upload_image(img, refresh_mode=RefreshMode.FULL if full_refresh else RefreshMode.FAST,
                                   dither_mode=DitherMode.NONE, fit=FitMode.STRETCH, rotate=Rotation.ROTATE_0,
                                   compress=bool(ref.keys.get("compress", True)))
        finally:
            await dev_cm.__aexit__(None, None, None)

    def capabilities(self):
        return {"supports_status": True, "supports_partial_refresh": True, "needs_pairing": True, "min_battery_mv": self.min_battery_mv}
