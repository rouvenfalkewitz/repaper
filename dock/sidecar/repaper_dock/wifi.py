"""Captive-portal Wi-Fi provisioning (SoftAP onboarding).

No known network for ~75 s → the Dock opens an open hotspot named after its
claim code, hijacks DNS (dnsmasq wildcard, installed by install-pi.sh) and
redirects port 80 (repaper-portal.service) so the phone's captive sheet opens
the setup page. Picking a network + password joins it and the hotspot vanishes.
Linux/NetworkManager only — on other hosts this thread never starts anything.

One radio = no scanning while in AP mode, so the network list is captured just
before the hotspot goes up. A hotspot always carries an auto-revert timer, so a
test can never strand a working Dock off its Wi-Fi.
"""
from __future__ import annotations
import logging, re, shutil, subprocess, sys, threading, time

log = logging.getLogger("repaper")

HOTSPOT_CON = "repaper-hotspot"
AP_IP = "10.42.0.1"


def _nmcli(*args: str, timeout: float = 30.0) -> subprocess.CompletedProcess:
    return subprocess.run(["nmcli", *args], capture_output=True, text=True, timeout=timeout)


class WifiOnboarding(threading.Thread):
    def __init__(self, dock):
        super().__init__(daemon=True, name="wifi-onboarding")
        self.dock = dock
        self.supported = sys.platform.startswith("linux") and shutil.which("nmcli") is not None
        code = dock.cloud.identity.get("claim_code", "0000") if hasattr(dock, "cloud") else "0000"
        self.ap_ssid = f"RePaper Dock {code.split('-')[0]}"
        self.mode = "normal"                 # normal | hotspot | joining
        self.detail = ""
        self.networks: list[dict] = []       # cached scan (an AP can't scan)
        self._offline_since: float | None = None
        self._revert_at: float | None = None

    def info(self) -> dict:
        return {"supported": self.supported, "mode": self.mode, "ap_ssid": self.ap_ssid,
                "detail": self.detail, "networks": self.networks,
                "current": self._current_ssid() if self.supported and self.mode == "normal" else None}

    # ── the watchdog ─────────────────────────────────────────────────────────
    def run(self) -> None:
        if not self.supported: return
        while True:
            time.sleep(15)
            try:
                if self.mode == "hotspot":
                    if self._revert_at and time.time() > self._revert_at:
                        log.info("wifi: hotspot timed out — returning to normal Wi-Fi")
                        self.hotspot_down()
                    continue
                if self.mode == "joining": continue
                if self._wifi_connected():
                    self._offline_since = None
                else:
                    self._offline_since = self._offline_since or time.time()
                    if time.time() - self._offline_since > 75:
                        self.hotspot_up(timeout_min=10)
            except Exception as e:
                log.debug("wifi watchdog: %s", e)

    def _wifi_connected(self) -> bool:
        r = _nmcli("-t", "-f", "DEVICE,STATE", "device")
        return any(l.startswith("wlan0:connected") for l in r.stdout.splitlines())

    def _current_ssid(self) -> str | None:
        r = _nmcli("-t", "-f", "ACTIVE,SSID", "device", "wifi")
        for l in r.stdout.splitlines():
            if l.startswith("yes:"): return l[4:] or None
        return None

    # ── scanning (cached before AP mode) ─────────────────────────────────────
    def scan(self) -> list[dict]:
        r = _nmcli("-t", "-f", "SSID,SIGNAL,SECURITY", "device", "wifi", "list", "--rescan", "yes", timeout=35)
        best: dict[str, dict] = {}
        for l in r.stdout.splitlines():
            parts = l.rsplit(":", 2)
            if len(parts) != 3 or not parts[0]: continue
            ssid, signal, sec = parts[0].replace("\\:", ":"), int(parts[1] or 0), parts[2]
            if ssid not in best or best[ssid]["signal"] < signal:
                best[ssid] = {"ssid": ssid, "signal": signal, "secured": sec not in ("", "--")}
        self.networks = sorted(best.values(), key=lambda n: -n["signal"])[:20]
        return self.networks

    # ── hotspot lifecycle ────────────────────────────────────────────────────
    def hotspot_up(self, timeout_min: int = 10) -> None:
        if self.mode == "hotspot":
            self._revert_at = time.time() + timeout_min * 60; return
        try: self.scan()
        except Exception: pass
        log.info("wifi: opening setup hotspot '%s' (auto-revert in %d min)", self.ap_ssid, timeout_min)
        _nmcli("connection", "delete", HOTSPOT_CON)
        _nmcli("connection", "add", "type", "wifi", "ifname", "wlan0", "con-name", HOTSPOT_CON,
               "autoconnect", "no", "ssid", self.ap_ssid, "802-11-wireless.mode", "ap",
               "802-11-wireless.band", "bg", "ipv4.method", "shared", "ipv4.addresses", f"{AP_IP}/24")
        r = _nmcli("connection", "up", HOTSPOT_CON, timeout=45)
        if r.returncode != 0:
            self.detail = (r.stderr or r.stdout).strip()[-200:]
            log.warning("wifi: hotspot failed: %s", self.detail); return
        subprocess.run(["sudo", "-n", "systemctl", "start", "repaper-portal"], capture_output=True)
        self.mode, self._revert_at = "hotspot", time.time() + timeout_min * 60

    def hotspot_down(self) -> None:
        subprocess.run(["sudo", "-n", "systemctl", "stop", "repaper-portal"], capture_output=True)
        _nmcli("connection", "down", HOTSPOT_CON)
        _nmcli("connection", "delete", HOTSPOT_CON)
        self.mode, self._offline_since, self._revert_at = "normal", None, None
        # NetworkManager autoconnects known networks by itself from here

    # ── joining a network from the setup page ────────────────────────────────
    def join(self, ssid: str, password: str) -> None:
        """Async: the phone loses this hotspot the moment we switch — the page
        explains that. Failure re-opens the hotspot with a hint."""
        def work():
            log.info("wifi: joining '%s'", ssid)
            was_hotspot = self.mode == "hotspot"
            self.mode = "joining"
            if was_hotspot:
                subprocess.run(["sudo", "-n", "systemctl", "stop", "repaper-portal"], capture_output=True)
                _nmcli("connection", "down", HOTSPOT_CON)
                _nmcli("connection", "delete", HOTSPOT_CON)
            args = ["device", "wifi", "connect", ssid, "ifname", "wlan0"]
            if password: args += ["password", password]
            r = _nmcli(*args, timeout=60)
            if r.returncode == 0 and self._wifi_connected():
                log.info("wifi: joined '%s'", ssid)
                self.mode, self.detail, self._offline_since = "normal", "", None
            else:
                self.detail = "could not join — wrong password?"
                log.warning("wifi: join '%s' failed: %s", ssid, (r.stderr or r.stdout).strip()[-200:])
                self.mode = "normal"
                if was_hotspot: self.hotspot_up(timeout_min=10)
        threading.Thread(target=work, daemon=True).start()
