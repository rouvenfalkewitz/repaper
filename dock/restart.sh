#!/usr/bin/env bash
# Restart the Dock on this Mac (printer + daemon), logging to ~/.repaper/dock.log. Safe to call from anywhere.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
for pat in "repaper-dockd run" "ippeveprinter -v" "bash ./run-mac.sh" "run-mac.sh"; do
  for pid in $(pgrep -f "$pat"); do [ "$pid" != "$$" ] && [ "$pid" != "$PPID" ] && kill "$pid" 2>/dev/null; done
done
sleep 2
mkdir -p "$HOME/.repaper"
cd "$HERE" && nohup ./run-mac.sh >> "$HOME/.repaper/dock.log" 2>&1 &
sleep 5
printf 'restarted · '; pgrep -f "repaper-dockd run" >/dev/null && printf 'daemon up · ' || printf 'daemon DOWN · '
pgrep -f "ippeveprinter -v" >/dev/null && echo 'printer up' || echo 'printer DOWN'
