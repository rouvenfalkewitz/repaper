"""Transport loading by configuration — never by build."""
from __future__ import annotations
from .base import SheetTransport, SheetRegistry


def load_transports(cfg: dict, registry: SheetRegistry) -> dict[str, SheetTransport]:
    out: dict[str, SheetTransport] = {}
    for tid in cfg.get("transports", []):
        if tid == "mock":
            from .mock import MockTransport
            out[tid] = MockTransport(cfg["mock_output_dir"], registry)
        elif tid == "opendisplay-ble":
            from .opendisplay_ble import OpenDisplayBLETransport
            out[tid] = OpenDisplayBLETransport(registry)
        else:
            raise ValueError(f"unknown transport '{tid}' in config")
    return out
