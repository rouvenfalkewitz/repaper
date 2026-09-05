#!/usr/bin/env bash
# Applies a downloaded RePaper release on a Pi Dock. Started detached by the
# cloud agent (which this script restarts, so it must run in its own session).
#   update-pi.sh <verified-tarball> <version>
# In-place update with a full backup: if the Dock isn't healthy again within
# 30 s of restarting, the previous version comes back automatically.
set -uo pipefail
TAR="$1"; VER="$2"
REPO="$HOME/repaper"
BACKUP="$HOME/repaper.prev"
LOG="${REPAPER_HOME:-$HOME/.repaper}/update.log"
health() { curl -sf --max-time 4 http://localhost:9631/api/status >/dev/null; }

{
  echo "== update to $VER — $(date -Is) =="
  rm -rf "$BACKUP" && cp -a "$REPO" "$BACKUP" || { echo "backup failed, aborting"; exit 1; }
  tar xzf "$TAR" -C "$REPO" || { echo "extract failed, aborting"; rm -rf "$BACKUP"; exit 1; }
  bash "$REPO/dock/install-pi.sh" || echo "install script failed — health check decides"
  sudo systemctl restart repaper-dockd repaper-printer
  ok=""
  for i in $(seq 1 15); do sleep 2; health && { ok=1; break; }; done
  if [ -n "$ok" ]; then
    echo "healthy on $VER — cleaning up"
    rm -rf "$BACKUP"; rm -f "$TAR"
  else
    echo "NOT healthy — rolling back"
    rm -rf "$REPO" && mv "$BACKUP" "$REPO"
    cd "$REPO/dock/sidecar" && .venv/bin/pip install -q -e .
    sudo systemctl restart repaper-dockd repaper-printer
    sleep 6; health && echo "rollback healthy" || echo "rollback ALSO unhealthy — needs hands"
  fi
} >> "$LOG" 2>&1
