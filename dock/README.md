# RePaper Dock — software

The Dock is two processes:

```
printer   ippeveprinter (macOS/CUPS, today) → PAPPL printer application (later)
          announces "RePaper Dock" via DNS-SD, speaks IPP/AirPrint/Mopria, receives the document,
          runs `repaper-print` with the document on stdin
sidecar   repaper-print   decodes PWG raster / Apple raster (URF) / JPEG / PNG into a spool job
          repaper-dockd   waits for a tap, renders the page for THAT sheet, sends it via a SheetTransport;
                          local web UI at http://localhost:9631/ (job inbox, manual tap)
```

Everything hardware-specific sits behind `repaper_dock/sheets/base.py` (`SheetTransport`, `SheetRegistry`) and
`repaper_dock/identify/base.py` (`SheetIdentifier`) — see `docs/05-architecture.md` §5. Transports today:
`mock` (writes PNGs) and `opendisplay-ble` (https://opendisplay.org via `py-opendisplay`).

## Run on a Mac (no hardware needed)

```
cd dock/sidecar
/opt/homebrew/bin/python3.12 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest

# a sheet to print on — a mock first
.venv/bin/repaper-dockd add-sheet mock-card --transport mock --address 400x300 --size 400x300 --palette BWR --name "Mock card"
.venv/bin/repaper-dockd test-page mock-card          # → ~/.repaper/mock-out/*.png

# your OpenDisplay label (MAC + key from its QR page; size/palette from the label)
.venv/bin/repaper-dockd discover
.venv/bin/repaper-dockd add-sheet label-1 --transport opendisplay-ble --address AA:BB:CC:DD:EE:FF --key <hex> --size 296x128 --palette BWR --name "SoluM 2.9"
.venv/bin/repaper-dockd test-page label-1

# the printer + the daemon
../run-mac.sh                                        # then print to "RePaper Dock" from any device; open http://localhost:9631/
```

State lives in `~/.repaper/` (`config.json`, `sheets.json`, `spool/`, `mock-out/`); override with `REPAPER_HOME`.

## Layout

```
sidecar/repaper_dock/
  config.py            paths, defaults
  sheets/base.py       the interface boundary: SheetTransport, SheetRegistry, Page, SheetModel, Palette
  sheets/mock.py       mock transport
  sheets/opendisplay_ble.py
  sheets/registry.py   load transports by configuration
  identify/            SheetIdentifier: manual (web UI / terminal); nfc-sticker, qr, ble-rssi later
  render/raster.py     PWG raster + Apple raster (URF) decoders
  render/decode.py     document → page images
  render/fit.py        page image → Page for a sheet (trim, rotate, fit, dither to palette)
  spool.py             jobs on disk
  dockd.py             the loop + web UI
  cli.py               repaper-print, repaper-dockd
tests/                 raster round-trips (reference encoder), rendering, mock printing
run-mac.sh             ippeveprinter + dockd on macOS
```

## Next

1. Print from an iPhone and a Mac to `run-mac.sh`'s printer; confirm URF/PWG decoding against real client output.
2. Print onto the real OpenDisplay label; fill `status()` from BLE advertisements (battery).
3. Replace `ippeveprinter` with a PAPPL printer application (sheet sizes as media, job state "media-needed" until tap).
4. `nfc-sticker` identifier (PN532) and the light ring, on the Pi.
