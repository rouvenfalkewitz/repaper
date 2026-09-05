#!/usr/bin/env bash
# RePaper Dock install on a Raspberry Pi (run ON the Pi, from the synced repo):
#   ~/repaper/dock/install-pi.sh
# Assumes the OS packages are present:
#   apt install git rsync python3-venv python3-dev python3-pip cups-ipp-utils cups-client avahi-daemon
# This script is the seed of the golden image build: everything a Dock needs
# beyond a stock Raspberry Pi OS Lite (64-bit) happens here, idempotently.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
USER_NAME="$(whoami)"

echo "== sidecar venv =="
cd "$REPO/dock/sidecar"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q --upgrade pip
.venv/bin/pip install -q -e .
.venv/bin/repaper-dockd --help >/dev/null 2>&1 || true

echo "== captive portal bits =="
echo 'address=/#/10.42.0.1' | sudo tee /etc/NetworkManager/dnsmasq-shared.d/repaper-captive.conf >/dev/null

echo "== systemd units =="
for unit in repaper-dockd repaper-printer repaper-portal; do
  sed -e "s|@REPO@|$REPO|g" -e "s|@USER@|$USER_NAME|g" \
    "$REPO/dock/systemd/$unit.service" | sudo tee "/etc/systemd/system/$unit.service" >/dev/null
done
sudo systemctl daemon-reload
sudo systemctl enable repaper-dockd repaper-printer >/dev/null 2>&1   # the portal unit stays disabled — started on demand by the hotspot

echo "== done — start with: sudo systemctl start repaper-dockd repaper-printer =="
