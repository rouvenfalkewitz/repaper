#!/usr/bin/env bash
# Run the RePaper printer on this Mac: ippeveprinter (ships with macOS) + the sidecar daemon.
# Print to "RePaper Dock" from your iPhone/Mac; the job appears at http://localhost:9631/ — tap a sheet there.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; VENV="$HERE/sidecar/.venv"
NAME="${REPAPER_PRINTER_NAME:-RePaper Dock}"
ICON="$HERE/../brand/logo/export/app-icon/ios/AppIcon-1024.png"
SPOOL="${REPAPER_HOME:-$HOME/.repaper}/ippeve"; mkdir -p "$SPOOL"
[ -x "$VENV/bin/repaper-print" ] || { echo "sidecar not installed: cd dock/sidecar && .venv/bin/pip install -e ."; exit 1; }
"$VENV/bin/repaper-dockd" run &
DOCKD=$!
trap 'kill $DOCKD 2>/dev/null || true' EXIT
echo "▶ ippeveprinter as “$NAME” — AirPrint (URF) + IPP Everywhere (PWG raster) + JPEG/PNG"
exec ippeveprinter -v -M RePaper -m "Dock" -l "on this Mac" \
  -f image/urf,image/pwg-raster,image/jpeg,image/png \
  -r universal,print -s 10 -i "$ICON" \
  -d "$SPOOL" -c "$VENV/bin/repaper-print" "$NAME"
