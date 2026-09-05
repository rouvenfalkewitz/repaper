#!/usr/bin/env bash
# Builds a RePaper Dock release tarball from the working tree:
#   dist/repaper-dock-<version>.tar.gz   (version = repaper_dock.__version__)
# Upload it on the console's Updates page (vendor admins), then roll it out.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
VER=$(python3 -c "import re;print(re.search(r'__version__ = \"([^\"]+)\"', open('$HERE/dock/sidecar/repaper_dock/__init__.py').read()).group(1))")
mkdir -p "$HERE/dist"
OUT="$HERE/dist/repaper-dock-$VER.tar.gz"
COPYFILE_DISABLE=1 tar czf "$OUT" -C "$HERE" \
  --exclude='dock/sidecar/.venv' --exclude='__pycache__' --exclude='*.egg-info' \
  dock brand/logo/export/app-icon/ios/AppIcon-1024.png
echo "$OUT (version $VER, $(du -h "$OUT" | cut -f1))"
