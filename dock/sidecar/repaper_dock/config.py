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
    "job_timeout_seconds": 3600,                     # a job nobody taps for is cancelled after this
    "sheet_cycle": False,                            # with several sheets: print on each in turn, no tap needed
    "dock_light": False,                             # Dock Light: no sheets — jobs are forwarded to the cloud mirror
    "web_port": 9631,
    "web_bind": "0.0.0.0",                           # the Dock's page is reachable on the network (phones on the same Wi-Fi)
    "status_refresh_seconds": 60,                    # how often sheet battery/online is re-read while idle
    "mock_output_dir": str(HOME / "mock-out"),
    "cloud_url": "",                                 # RePaper Cloud device channel (wss://…/ws/device); empty = local-only

}

def load_config() -> dict:
    if CONFIG.exists():
        cfg = dict(DEFAULT_CONFIG); cfg.update(json.loads(CONFIG.read_text())); return cfg
    return dict(DEFAULT_CONFIG)

def ensure_home() -> None:
    HOME.mkdir(parents=True, exist_ok=True); SPOOL.mkdir(exist_ok=True)
    if not CONFIG.exists(): CONFIG.write_text(json.dumps(DEFAULT_CONFIG, indent=2) + "\n")
