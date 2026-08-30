"""The hard interface boundary between RePaper and any e-paper hardware.

Nothing above this module knows what a sheet physically is. See docs/05-architecture.md §5.

  SheetTransport   one per physical path (mock, opendisplay-ble, openepaperlink-ap, solum-*, nfc-direct)
  SheetRegistry    the only place that maps a sheet id to {transport, address, keys, model}
Rendering (fit, rotate, dither) happens ABOVE this line; a transport receives a Page that is already
an indexed bitmap in the sheet's palette at the sheet's exact pixel size, and only transmits it.
"""
from __future__ import annotations
from abc import ABC, abstractmethod
from dataclasses import dataclass, field, asdict
from typing import Any, Optional
import json
from PIL import Image


@dataclass(frozen=True)
class Palette:
    name: str
    colors: tuple[tuple[int, int, int], ...]     # index order is the order transports expect


# Pure colours on purpose: transports/SDKs map them to the panel's inks; the renderer never emits anything else.
PALETTES: dict[str, Palette] = {
    "BW":   Palette("BW",   ((255, 255, 255), (0, 0, 0))),
    "BWR":  Palette("BWR",  ((255, 255, 255), (0, 0, 0), (255, 0, 0))),
    "BWRY": Palette("BWRY", ((255, 255, 255), (0, 0, 0), (255, 0, 0), (255, 255, 0))),
}


@dataclass
class SheetModel:
    width: int                      # pixels, in the sheet's native orientation
    height: int
    palette: str = "BW"             # key of PALETTES
    rotation: int = 0               # degrees the transport applies on top (0/90/180/270); renderer targets width×height as given
    refresh_seconds: Optional[float] = None
    model_name: Optional[str] = None
    inset: tuple[int, int, int, int] = (0, 0, 0, 0)   # left, top, right, bottom — pixels that exist in the data but not on the glass

    @property
    def colors(self) -> Palette: return PALETTES[self.palette]

    @property
    def visible(self) -> tuple[int, int, int, int]:
        """(x0, y0, x1, y1) of the area content may use, in viewed pixels."""
        l, t, r, b = self.inset; return (l, t, self.width - r, self.height - b)


@dataclass
class SheetRef:
    """How a transport addresses one sheet. `address`/`keys` are opaque to everything but the transport."""
    transport_id: str
    address: str
    keys: dict[str, Any] = field(default_factory=dict)
    name: Optional[str] = None


@dataclass
class SheetStatus:
    """Best-effort. A path that cannot tell returns None — never a fake value."""
    battery_percent: Optional[float] = None
    battery_volts: Optional[float] = None
    temperature_c: Optional[float] = None
    firmware: Optional[str] = None
    last_seen: Optional[float] = None       # unix time
    online: Optional[bool] = None


@dataclass
class Page:
    """A page already rendered for a specific sheet: mode 'P' image, palette == model.colors, size == model size."""
    image: Image.Image
    model: SheetModel

    def __post_init__(self):
        assert self.image.mode == "P", "Page.image must be an indexed ('P') image"
        assert self.image.size == (self.model.width, self.model.height), "Page must be at the sheet's exact size"


@dataclass
class PrintResult:
    ok: bool
    seconds: float = 0.0
    message: str = ""


class TransportError(RuntimeError):
    pass


class SheetTransport(ABC):
    """One physical path to sheets. Implementations must be safe to construct without hardware present."""

    @property
    @abstractmethod
    def id(self) -> str: ...

    @abstractmethod
    def discover(self, timeout: float = 5.0) -> list[SheetRef]:
        """Sheets reachable right now. May be empty; must not raise when no hardware is present."""

    @abstractmethod
    def describe(self, ref: SheetRef) -> SheetModel:
        """Size/palette of this sheet. May consult the registry entry or the device itself."""

    @abstractmethod
    def status(self, ref: SheetRef) -> SheetStatus: ...

    @abstractmethod
    def print(self, ref: SheetRef, page: Page, *, full_refresh: bool = True) -> PrintResult:
        """Transmit an already-rendered page. Must not dither, scale or recolour."""

    def capabilities(self) -> dict[str, Any]:
        return {"supports_status": False, "supports_partial_refresh": False, "needs_pairing": False}


class SheetRegistry:
    """JSON file: sheet id → {name, transport, address, keys, model}. The only id→hardware mapping in the system."""

    def __init__(self, path):
        self.path = path; self._data: dict[str, dict] = {}
        if path.exists(): self._data = json.loads(path.read_text())

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self._data, indent=2) + "\n")

    def ids(self) -> list[str]: return list(self._data)

    def get(self, sheet_id: str) -> tuple[SheetRef, SheetModel]:
        e = self._data[sheet_id]
        m = dict(e["model"]); m["inset"] = tuple(m.get("inset", (0, 0, 0, 0)))
        return (SheetRef(e["transport"], e["address"], dict(e.get("keys", {})), e.get("name")), SheetModel(**m))

    def add(self, sheet_id: str, ref: SheetRef, model: SheetModel) -> None:
        self._data[sheet_id] = {"name": ref.name, "transport": ref.transport_id, "address": ref.address,
                                "keys": ref.keys, "model": asdict(model)}
        self.save()

    def find_by_address(self, transport_id: str, address: str) -> Optional[str]:
        for k, e in self._data.items():
            if e["transport"] == transport_id and e["address"].lower() == address.lower(): return k
        return None

    def all(self) -> dict[str, dict]: return dict(self._data)
