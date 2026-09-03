#!/usr/bin/env bash
# Launches ippeveprinter on a Pi with the printer name from the Dock's config.
# Started by repaper-printer.service — not meant to be run by hand.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
HOME_DIR="${REPAPER_HOME:-$HOME/.repaper}"
CFG="$HOME_DIR/config.json"
NAME="$( [ -f "$CFG" ] && python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('printer_name','RePaper Dock'))" "$CFG" 2>/dev/null || echo "RePaper Dock")"
IPPEVE="$(command -v ippeveprinter || echo /usr/sbin/ippeveprinter)"
ICON="$HERE/../brand/logo/export/app-icon/ios/AppIcon-1024.png"
SPOOL="$HOME_DIR/ippeve"; mkdir -p "$SPOOL"
exec "$IPPEVE" -v -M RePaper -m "Dock" -l "RePaper Pilot" \
  -f image/urf,image/pwg-raster,image/jpeg,image/png \
  -r _universal,_print -s 10 -i "$ICON" \
  -d "$SPOOL" -c "$HERE/sidecar/.venv/bin/repaper-print" "$NAME"
