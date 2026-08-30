"""Configuration and paths. Everything lives under ~/.repaper (override with REPAPER_HOME)."""
from __future__ import annotations
import json, os
from pathlib import Path

HOME = Path(os.environ.get("REPAPER_HOME", Path.home() / ".repaper"))
SPOOL = HOME / "spool"
CONFIG = HOME / "config.json"
SHEETS = HOME / "sheets.json"

DEFAULT_CONFIG = {
    "printer_name": "RePaper Dock",
    "transports": ["mock", "opendisplay-ble"],      # loaded in this order; a sheet's registry entry says which one delivers to it
    "identifier": "manual",                          # manual | nfc-sticker | qr | ble-rssi
    "job_timeout_seconds": 600,                      # a job nobody taps for is cancelled after this
    "web_port": 9631,
    "mock_output_dir": str(HOME / "mock-out"),
}

def load_config() -> dict:
    if CONFIG.exists():
        cfg = dict(DEFAULT_CONFIG); cfg.update(json.loads(CONFIG.read_text())); return cfg
    return dict(DEFAULT_CONFIG)

def ensure_home() -> None:
    HOME.mkdir(parents=True, exist_ok=True); SPOOL.mkdir(exist_ok=True)
    if not CONFIG.exists(): CONFIG.write_text(json.dumps(DEFAULT_CONFIG, indent=2) + "\n")
