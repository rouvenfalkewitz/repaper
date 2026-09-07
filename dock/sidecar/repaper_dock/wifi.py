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
import logging, os, re, shutil, socket, subprocess, sys, threading, time

log = logging.getLogger("repaper")

HOTSPOT_CON = "repaper-hotspot"
AP_IP = "10.42.0.1"


def _nmcli(*args: str, timeout: float = 30.0) -> subprocess.CompletedProcess:
    # polkit denies an unprivileged service the right to add/modify connections
    # ("Insufficient privileges"), so on the appliance nmcli runs through sudo
    cmd = ["nmcli", *args]
    if os.geteuid() != 0 and shutil.which("sudo"): cmd = ["sudo", "-n", *cmd]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


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
        out = {"supported": self.supported, "mode": self.mode, "ap_ssid": self.ap_ssid,
               "hostname": socket.gethostname(), "detail": self.detail, "networks": self.networks,
               "current": self._current_ssid() if self.supported and self.mode == "normal" else None}
        if self.supported and self.mode == "normal":
            try: out["net"] = self.net_config()
            except Exception: pass
        return out

    def _active_con(self) -> str | None:
        r = _nmcli("-t", "-f", "NAME,DEVICE", "connection", "show", "--active")
        for l in r.stdout.splitlines():
            name, _, dev = l.rpartition(":")
            if dev == "wlan0" and name != HOTSPOT_CON: return name
        return None

    def net_config(self) -> dict:
        con = self._active_con()
        if not con: return {"method": "auto"}
        r = _nmcli("-g", "ipv4.method,ipv4.addresses,ipv4.gateway,ipv4.dns", "connection", "show", con)
        v = (r.stdout.splitlines() + ["", "", "", ""])[:4]
        return {"method": "manual" if v[0] == "manual" else "auto",
                "address": v[1].split(",")[0].replace("\\", ""), "gateway": v[2],
                "dns": v[3].replace(",", " ").strip()}

    def apply_net(self, method: str, address: str = "", gateway: str = "", dns: str = "") -> None:
        """Applied async — re-activating the connection can drop the page briefly.
        A broken static config is recoverable: the watchdog notices the dead
        gateway and reopens the setup hotspot."""
        con = self._active_con()
        if not con: raise ValueError("no active Wi-Fi connection to configure")
        self._cfg_at = 0.0
        def work():
            log.info("wifi: applying %s network config on '%s'", method, con)
            if method == "manual":
                _nmcli("connection", "modify", con, "ipv4.method", "manual",
                       "ipv4.addresses", address, "ipv4.gateway", gateway,
                       "ipv4.dns", dns.replace(" ", ","))
            else:
                _nmcli("connection", "modify", con, "ipv4.method", "auto",
                       "ipv4.gateway", "", "ipv4.dns", "")
                _nmcli("connection", "modify", con, "ipv4.addresses", "")
            _nmcli("connection", "up", con, timeout=45)
        threading.Thread(target=work, daemon=True).start()

    # ── the watchdog ─────────────────────────────────────────────────────────
    def _known_wifi(self) -> bool:
        r = _nmcli("-t", "-f", "NAME,TYPE", "connection", "show")
        return any(l.endswith(":802-11-wireless") and not l.startswith(HOTSPOT_CON + ":")
                   for l in r.stdout.splitlines())

    def run(self) -> None:
        if not self.supported: return
        time.sleep(8)                        # let NetworkManager settle after boot
        try:
            if not self._known_wifi():       # factory-fresh: nothing to wait for
                log.info("wifi: no known networks — opening setup hotspot right away")
                self.hotspot_up(timeout_min=30)
        except Exception as e:
            log.debug("wifi first-boot check: %s", e)
        while True:
            time.sleep(15)
            try:
                if self.mode == "hotspot":
                    if self._revert_at and time.time() > self._revert_at:
                        if not self._known_wifi():
                            self._revert_at = time.time() + 600   # nowhere to revert to — stay open
                            continue
                        log.info("wifi: hotspot timed out — returning to normal Wi-Fi")
                        self.hotspot_down()
                    continue
                if self.mode == "joining": continue
                if self._wifi_connected():
                    self._offline_since = None
                else:
                    self._offline_since = self._offline_since or time.time()
                    # 2.4 GHz links flap (BLE scans share the band) — don't open the
                    # hotspot for a hiccup NetworkManager will heal by itself
                    if time.time() - self._offline_since > 150:
                        self.hotspot_up(timeout_min=10)
            except Exception as e:
                log.debug("wifi watchdog: %s", e)

    def _wifi_connected(self) -> bool:
        r = _nmcli("-t", "-f", "DEVICE,STATE", "device")
        if not any(l.startswith("wlan0:connected") for l in r.stdout.splitlines()): return False
        # a wrong static config looks "connected" — for manual configs, require the
        # gateway to actually answer (a few misses in a row, to forgive lost pings).
        # The config is cached (~5 min): re-reading it every tick spams nmcli + the sudo journal.
        try:
            now = time.time()
            if now - getattr(self, "_cfg_at", 0) > 300:
                self._cfg_cache, self._cfg_at = self.net_config(), now
            cfg = getattr(self, "_cfg_cache", {})
            if cfg.get("method") == "manual" and cfg.get("gateway"):
                ok = subprocess.run(["ping", "-c1", "-W1", cfg["gateway"]], capture_output=True).returncode == 0
                self._gw_fails = 0 if ok else getattr(self, "_gw_fails", 0) + 1
                if self._gw_fails >= 5:
                    log.warning("wifi: static config looks dead (gateway silent ×%d)", self._gw_fails)
                    return False
        except Exception: pass
        return True

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
        # the portal (port 80, binds 0.0.0.0) must answer the phone's very first
        # captive-portal probe — start it before the AP exists, not after
        subprocess.run(["sudo", "-n", "systemctl", "start", "repaper-portal"], capture_output=True)
        _nmcli("connection", "delete", HOTSPOT_CON)
        _nmcli("connection", "add", "type", "wifi", "ifname", "wlan0", "con-name", HOTSPOT_CON,
               "autoconnect", "no", "ssid", self.ap_ssid, "802-11-wireless.mode", "ap",
               "802-11-wireless.band", "bg", "ipv4.method", "shared", "ipv4.addresses", f"{AP_IP}/24")
        r = _nmcli("connection", "up", HOTSPOT_CON, timeout=45)
        if r.returncode != 0:
            self.detail = (r.stderr or r.stdout).strip()[-200:]
            subprocess.run(["sudo", "-n", "systemctl", "stop", "repaper-portal"], capture_output=True)
            log.warning("wifi: hotspot failed: %s", self.detail); return
        self.mode, self._revert_at = "hotspot", time.time() + timeout_min * 60

    def hotspot_down(self) -> None:
        subprocess.run(["sudo", "-n", "systemctl", "stop", "repaper-portal"], capture_output=True)
        _nmcli("connection", "down", HOTSPOT_CON)
        _nmcli("connection", "delete", HOTSPOT_CON)
        self.mode, self._offline_since, self._revert_at = "normal", None, None
        # NetworkManager autoconnects known networks by itself from here
        self._mdns_refresh()

    def _mdns_refresh(self) -> None:
        """After interface flaps (hotspot cycles, joins) Avahi can collide with its own
        ghost records and rename the host (repaper-pilot-2.local) — which strands the
        printer's AirPrint records. A clean restart of both re-registers everything."""
        def work():
            time.sleep(5)                     # let the interface settle first
            subprocess.run(["sudo", "-n", "systemctl", "restart", "avahi-daemon"], capture_output=True)
            time.sleep(2)
            subprocess.run(["sudo", "-n", "systemctl", "restart", "repaper-printer"], capture_output=True)
            log.info("wifi: refreshed mDNS (avahi + printer) after network change")
        threading.Thread(target=work, daemon=True).start()

    # ── joining a network from the setup page ────────────────────────────────
    def _drop_profiles(self, ssid: str) -> None:
        """Delete profiles a `device wifi connect` created for this SSID (nmcli
        names them after it: 'foo', 'foo-1', …). A failed connect leaves such a
        profile behind, and broken ones fight the working connection at every
        reconnect. Profiles from the OS install (netplan-*) are never touched."""
        r = _nmcli("-t", "-f", "NAME", "connection", "show")
        for name in r.stdout.splitlines():
            base, _, suffix = name.rpartition("-")
            if name == ssid or (base == ssid and suffix.isdigit()):
                _nmcli("connection", "delete", name)

    def join(self, ssid: str, password: str, static: dict | None = None) -> None:
        """Async: the phone loses this hotspot the moment we switch — the page
        explains that. Failure re-opens the hotspot with the reason. Right after
        AP mode the scan cache is empty, so each attempt rescans first."""
        def work():
            log.info("wifi: joining '%s'", ssid)
            was_hotspot = self.mode == "hotspot"
            self.mode = "joining"
            if was_hotspot:
                subprocess.run(["sudo", "-n", "systemctl", "stop", "repaper-portal"], capture_output=True)
                _nmcli("connection", "down", HOTSPOT_CON)
                _nmcli("connection", "delete", HOTSPOT_CON)
            self._drop_profiles(ssid)
            err = ""
            for attempt in range(3):
                # an explicit profile needs no scan results: right after AP teardown
                # the scan cache is empty, and `device wifi connect` then fails with
                # "key-mgmt: property is missing" because it can't infer security
                self._drop_profiles(ssid)
                args = ["connection", "add", "type", "wifi", "con-name", ssid, "ifname", "wlan0",
                        "ssid", ssid, "connection.autoconnect", "yes"]
                if password: args += ["wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", password]
                r = _nmcli(*args)
                if r.returncode == 0:
                    time.sleep(1 + attempt * 2)
                    r = _nmcli("connection", "up", ssid, timeout=75)
                if r.returncode == 0 and self._wifi_connected():
                    log.info("wifi: joined '%s' (attempt %d)", ssid, attempt + 1)
                    self.mode, self.detail, self._offline_since = "normal", "", None
                    self._mdns_refresh()
                    if static and static.get("address"):
                        try: self.apply_net("manual", static.get("address", ""), static.get("gateway", ""), static.get("dns") or static.get("gateway", ""))
                        except Exception as e: log.warning("wifi: static config after join failed: %s", e)
                    return
                err = (r.stderr or r.stdout).strip()[-200:]
                log.warning("wifi: join '%s' attempt %d failed: %s", ssid, attempt + 1, err)
                self._drop_profiles(ssid)          # never leave a broken profile behind
            low = err.lower()
            if "no network" in low or "not found" in low:
                self.detail = f"Couldn\u2019t find \u201c{ssid}\u201d \u2014 is it in range? Give it another try."
            elif "secrets" in low or "password" in low or "802.1x" in low or "auth" in low:
                self.detail = f"Joining \u201c{ssid}\u201d didn\u2019t work \u2014 wrong password, or a weak signal at the Dock. Worth another try."
            else:
                self.detail = f"Joining \u201c{ssid}\u201d didn\u2019t work ({err[:80]})." if err else f"Joining \u201c{ssid}\u201d didn\u2019t work."
            self.mode = "normal"
            if was_hotspot: self.hotspot_up(timeout_min=10)
        threading.Thread(target=work, daemon=True).start()
