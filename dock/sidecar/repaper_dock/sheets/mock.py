"""Mock transport: 'prints' by writing the page as a PNG. Makes the whole pipeline testable without hardware."""
from __future__ import annotations
import time
from pathlib import Path
from .base import SheetTransport, SheetRef, SheetModel, SheetStatus, Page, PrintResult


class MockTransport(SheetTransport):
    def __init__(self, out_dir: str | Path, registry=None):
        self.out_dir = Path(out_dir); self.registry = registry

    @property
    def id(self) -> str: return "mock"

    def discover(self, timeout: float = 5.0) -> list[SheetRef]:
        if not self.registry: return []
        return [self.registry.get(i)[0] for i, e in self.registry.all().items() if e["transport"] == self.id]

    def describe(self, ref: SheetRef) -> SheetModel:
        sid = self.registry.find_by_address(self.id, ref.address) if self.registry else None
        if sid: return self.registry.get(sid)[1]
        w, h = (int(v) for v in ref.address.split("x"))       # mock addresses are "WxH"
        return SheetModel(w, h, ref.keys.get("palette", "BW"))

    def status(self, ref: SheetRef) -> SheetStatus:
        return SheetStatus(online=True, last_seen=time.time())

    def print(self, ref: SheetRef, page: Page, *, full_refresh: bool = True) -> PrintResult:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        name = (ref.name or ref.address).replace(" ", "_")
        path = self.out_dir / f"{name}-{time.strftime('%Y%m%d-%H%M%S')}.png"
        page.image.convert("RGB").save(path)
        return PrintResult(True, 0.0, f"written {path}")

    def capabilities(self): return {"supports_status": True, "supports_partial_refresh": False, "needs_pairing": False}
