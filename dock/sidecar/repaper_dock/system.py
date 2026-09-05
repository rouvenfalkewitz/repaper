"""Maintenance actions: reboot and factory reset. Linux Docks only.

Factory reset = the Dock forgets everything personal — sheets and their keys,
cloud identity and claim, Wi-Fi credentials, spool and history — and reboots
as if unboxed: it will open its setup hotspot and show a fresh claim code.
The software version stays; only data goes.
"""
from __future__ import annotations
import logging, shutil, subprocess, sys, threading, time
from .config import HOME

log = logging.getLogger("repaper")

def supported() -> bool:
    return sys.platform.startswith("linux")

def reboot(delay: float = 1.5) -> None:
    if not supported(): raise ValueError("reboot is not available on this host")
    log.info("system: reboot requested")
    def go():
        time.sleep(delay)
        subprocess.run(["sudo", "-n", "reboot"])
    threading.Thread(target=go, daemon=True).start()

def factory_reset(delay: float = 2.0) -> None:
    if not supported(): raise ValueError("factory reset is not available on this host")
    log.warning("system: FACTORY RESET requested — wiping state and Wi-Fi, then rebooting")
    def go():
        time.sleep(delay)
        try: shutil.rmtree(HOME, ignore_errors=True)          # sheets+keys, cloud identity, config, spool
        except Exception as e: log.error("factory reset: state wipe failed: %s", e)
        try:                                                   # forget every Wi-Fi network
            r = subprocess.run(["nmcli", "-t", "-f", "UUID,TYPE", "connection", "show"],
                               capture_output=True, text=True)
            for line in r.stdout.splitlines():
                uuid, _, ctype = line.rpartition(":")
                if "wireless" in ctype:
                    subprocess.run(["nmcli", "connection", "delete", uuid], capture_output=True)
        except Exception as e: log.error("factory reset: wifi forget failed: %s", e)
        subprocess.run(["sudo", "-n", "reboot"])
    threading.Thread(target=go, daemon=True).start()
