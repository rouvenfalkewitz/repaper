"""Cloud agent: one outbound WebSocket to RePaper Cloud (fleet view, claiming, identify).
Printing never depends on it — with no cloud_url configured, or the cloud unreachable,
the Dock just keeps working. The cloud sees metadata (status, sheet readings), never pages."""
from __future__ import annotations
import asyncio, hashlib, json, logging, secrets, subprocess, sys, threading, time, urllib.error, urllib.request
from pathlib import Path
from . import __version__
from .config import HOME
from .spool import list_jobs

log = logging.getLogger("repaper")


def _identity() -> dict:
    """Key material generated on first start: a device id, a secret (proves the id on
    reconnect), and the human claim code shown in Settings. Never leaves ~/.repaper."""
    p = HOME / "cloud.json"
    if p.exists():
        try:
            d = json.loads(p.read_text())
            if all(k in d for k in ("device_id", "secret", "claim_code")): return d
        except Exception: pass
    ident = {"device_id": secrets.token_hex(16), "secret": secrets.token_urlsafe(32),
             "claim_code": f"{secrets.token_hex(2).upper()}-{secrets.token_hex(2).upper()}"}
    HOME.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(ident, indent=2) + "\n"); p.chmod(0o600)
    return ident


class CloudAgent(threading.Thread):
    def __init__(self, dock):
        super().__init__(daemon=True, name="cloud-agent")
        self.dock = dock
        self.identity = _identity()
        self.state = "off"          # off | connecting | online | error
        self.detail = ""            # last error, for Settings
        self.claimed: bool | None = None
        self.org: str | None = None
        self._updating = False
        self.updating_version: str | None = None
        self.peers: list = []               # Print2Go: the phones printing from this Dock
        threading.Thread(target=self._update_check_loop, daemon=True, name="update-check").start()

    # ── what Settings shows ───────────────────────────────────────────────────
    def info(self) -> dict:
        return {"url": self.dock.cfg.get("cloud_url", ""), "state": self.state, "detail": self.detail,
                "claimed": self.claimed, "org": self.org, "peers": self.peers,
                "claim_code": self.identity["claim_code"], "device_id": self.identity["device_id"]}

    # ── the status heartbeat: metadata only, never job content ────────────────
    def _status(self) -> dict:
        s = self.dock.snapshot()
        lt = time.localtime()
        midnight = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, 0, 0, 0, 0, 0, -1))
        lan = ""
        try:
            import socket as _so
            with _so.socket(_so.AF_INET, _so.SOCK_DGRAM) as _s:
                _s.connect(("192.0.2.1", 80))
                lan = f"http://{_s.getsockname()[0]}:{self.dock.cfg['web_port']}/"
        except Exception: pass
        return {"t": "status", "printer": s["printer"], "state": s["state"], "version": __version__,
                "identifier": s["identifier"], "web": s["address"], "lan": lan,
                "print2go": bool(self.dock.cfg.get("dock_light") or self.dock.cfg.get("print2go")),
                "jobs_today": sum(1 for j in list_jobs(("done",)) if j.created >= midnight),
                "sheets": [{"id": k, "name": v["name"], "size": v["size"], "palette": v["palette"],
                            "battery_volts": v.get("battery_volts"), "temperature_c": v.get("temperature_c"),
                            "online": v.get("online"), "seen": v.get("seen"),
                            "last": {"at": v["last"]["at"]} if v.get("last") else None}
                           for k, v in s["sheets"].items()]}

    def run(self):
        try: asyncio.run(self._main())
        except Exception as e: log.error("cloud agent stopped: %s", e)

    async def _main(self):
        try: import websockets
        except ImportError:
            self.state, self.detail = "error", "the websockets package is not installed"
            log.warning("cloud: websockets package missing — cloud stays off"); return
        backoff = 5
        while True:
            url = (self.dock.cfg.get("cloud_url") or "").strip()
            if not url:
                self.state, self.detail, self.claimed, self.org = "off", "", None, None
                await asyncio.sleep(3); continue
            try:
                self.state, self.detail = "connecting", ""
                async with websockets.connect(url, open_timeout=10, ping_interval=20, ping_timeout=20, max_size=1 << 20) as ws:
                    await ws.send(json.dumps({"t": "hello", "id": self.identity["device_id"], "secret": self.identity["secret"],
                                              "claim": self.identity["claim_code"],
                                              "kind": "dock-light" if self.dock.cfg.get("dock_light") else "dock",
                                              "name": self.dock.cfg["printer_name"], "version": __version__}))
                    first = json.loads(await asyncio.wait_for(ws.recv(), 15))
                    if first.get("t") != "hello_ok": raise RuntimeError(first.get("error") or "unexpected reply")
                    self.state, self.claimed, self.org = "online", bool(first.get("claimed")), first.get("org")
                    backoff = 5
                    log.info("cloud: connected to %s (%s)", url,
                             f"claimed by {self.org}" if self.claimed else f"not claimed yet — code {self.identity['claim_code']}")
                    await ws.send(json.dumps(self._status())); last = time.time()
                    while (self.dock.cfg.get("cloud_url") or "").strip() == url:   # a URL change in Settings drops the link
                        try: raw = await asyncio.wait_for(ws.recv(), timeout=5)
                        except asyncio.TimeoutError: raw = None
                        if raw is not None:
                            try: r = self._on_message(json.loads(raw))
                            except RuntimeError: raise                     # server said error → reconnect
                            except Exception as e:                          # a bad message must never kill the link
                                import traceback
                                r = None; log.warning("cloud: message handling failed: %s\n%s", e, traceback.format_exc())
                            if r: await ws.send(json.dumps(r))
                        if time.time() - last >= self.dock.cfg.get("status_refresh_seconds", 60):
                            await ws.send(json.dumps(self._status())); last = time.time()
            except Exception as e:
                self.state, self.detail = "error", str(e) or type(e).__name__
                log.debug("cloud: %s", e)
                await asyncio.sleep(backoff); backoff = min(backoff * 2, 60)
            else:
                await asyncio.sleep(1)   # clean drop (URL change / server close) → reconnect promptly

    def _on_message(self, msg: dict) -> dict | None:
        t = msg.get("t")
        if t == "identify":
            log.info("cloud: identify — someone in the console is looking for this Dock (ring lights up once we have one)")
        elif t == "claimed":
            self.claimed, self.org = True, msg.get("org")
            log.info("cloud: claimed by %s", self.org)
        elif t == "reboot":
            log.info("cloud: reboot requested from the console")
            from . import system
            try: system.reboot(delay=2.0)
            except Exception as e: log.warning("cloud: reboot failed: %s", e)
        elif t == "diag":
            log.info("cloud: diagnostics requested — sending the log tail")
            try: tail = "".join((HOME / "dock.log").read_text(errors="replace").splitlines(keepends=True)[-200:])
            except Exception as e: tail = f"could not read the log: {e}"
            return {"t": "diag", "log": tail}
        elif t == "update":
            version, url, sha = str(msg.get("version", "")), str(msg.get("url", "")), str(msg.get("sha256", ""))
            script = Path(__file__).resolve().parents[2] / "update-pi.sh"
            if not (script.exists() and sys.platform.startswith("linux")):
                log.warning("cloud: update to %s requested but this host has no updater (dev machine?)", version)
                return {"t": "update_failed", "error": "no updater on this host"}
            if self._updating: return None
            self._start_update(version, url, sha)
            return {"t": "updating", "version": version}
        elif t == "print2go_peers":
            self.peers = msg.get("peers") or []
        elif t == "mirror_taken":
            jid = (msg.get("job") or {}).get("id")
            if jid: self.dock._p2g_taken.add(jid)
        elif t == "mirror_done":
            j = msg.get("job") or {}
            if j.get("id"): self.dock._p2g_done[j["id"]] = j.get("on") or "a phone"
        elif t == "error":
            raise RuntimeError(msg.get("error") or "server error")
        return None

    # ── Print2Go: the shared-pool relay (one page = one claimable job) ───────
    def forward_page(self, job, page_no: int) -> tuple[bool, str | None, str]:
        """Push one page into the pool; returns (ok, cloud_job_id, msg)."""
        base = self._http_base()
        if not base: return False, None, "no cloud configured"
        if self.claimed is False: return False, None, "claim this Dock in the console first"
        import base64, io
        from PIL import Image
        try:
            img = Image.open(job.page_path(page_no)).convert("RGB")
            buf = io.BytesIO(); img.save(buf, "PNG")
            name = job.name if job.pages == 1 else f"{job.name} (page {page_no} of {job.pages})"
            resp = self._post("/api/device/forward-job",
                              {"name": name, "type": "png", "data": base64.b64encode(buf.getvalue()).decode()})
            if not resp.get("ok"): return False, None, resp.get("error", "the cloud refused the job")
            return True, resp.get("job_id"), f"{resp.get('phones', 0)} phone(s)"
        except Exception as e:
            return False, None, self._err(e)

    def take(self, cloud_job_id: str) -> bool:
        """Claim a job for local printing (first wins). True if this Dock won it."""
        try:
            return bool(self._post(f"/api/device/mirror-job/{cloud_job_id}/take").get("ok"))
        except urllib.error.HTTPError as e:
            return False   # 409 taken, 404 gone — either way not ours
        except Exception:
            return False

    def job_done(self, cloud_job_id: str) -> None:
        try: self._post(f"/api/device/mirror-job/{cloud_job_id}/done")
        except Exception: pass

    def job_release(self, cloud_job_id: str) -> None:
        try: self._post(f"/api/device/mirror-job/{cloud_job_id}/release")
        except Exception: pass

    def _post(self, path: str, extra: dict | None = None) -> dict:
        base = self._http_base()
        body = {"id": self.identity["device_id"], "secret": self.identity["secret"], **(extra or {})}
        req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode() or "{}")

    @staticmethod
    def _err(e) -> str:
        if isinstance(e, urllib.error.HTTPError):
            try: return json.loads(e.read().decode()).get("error", str(e))
            except Exception: return str(e)
        return str(e) or type(e).__name__

    def _http_base(self) -> str:
        u = (self.dock.cfg.get("cloud_url") or "").strip()
        if u.startswith("wss://"): u = "https://" + u[6:]
        elif u.startswith("ws://"): u = "http://" + u[5:]
        return u.split("/ws/")[0]

    def _update_check_loop(self) -> None:
        """The pull path: a WebSocket needs calm air, a 2-second HTTPS request doesn't.
        Every few minutes the Dock asks the cloud whether a target version is set —
        updates arrive even when BLE traffic keeps trampling the socket."""
        time.sleep(20)
        while True:
            try:
                base = self._http_base()
                if base and not self._updating:
                    req = urllib.request.Request(base + "/api/device/update-check",
                        data=json.dumps({"id": self.identity["device_id"], "secret": self.identity["secret"],
                                         "version": __version__}).encode(),
                        headers={"Content-Type": "application/json"})
                    with urllib.request.urlopen(req, timeout=20) as r:
                        j = json.loads(r.read() or b"{}")
                    if j.get("version") and j["version"] != __version__:
                        log.info("cloud: update check found %s", j["version"])
                        self._start_update(j["version"], j["url"], j["sha256"])
            except Exception as e:
                log.debug("cloud: update check: %s", e)
            time.sleep(300)

    def _start_update(self, version: str, url: str, sha256: str) -> None:
        """One entry point for both trigger paths (socket offer and HTTPS poll)."""
        script = Path(__file__).resolve().parents[2] / "update-pi.sh"
        if not (script.exists() and sys.platform.startswith("linux")):
            log.warning("cloud: update to %s requested but this host has no updater", version); return
        if self._updating: return
        self._updating, self.updating_version = True, version
        log.info("cloud: updating to %s", version)
        threading.Thread(target=self._do_update, args=(script, version, url, sha256), daemon=True).start()

    def _do_update(self, script: Path, version: str, url: str, sha256: str) -> None:
        """Download, verify, then hand over to the detached updater script —
        which restarts this very process, so it must outlive us (own session)."""
        try:
            tar = Path(f"/tmp/repaper-{version}.tar.gz")
            with urllib.request.urlopen(url, timeout=120) as r: tar.write_bytes(r.read())
            digest = hashlib.sha256(tar.read_bytes()).hexdigest()
            if digest != sha256:
                log.error("cloud: update %s checksum mismatch — refusing", version); tar.unlink(missing_ok=True); return
            log.info("cloud: update %s downloaded and verified (%d bytes) — handing over to the updater", version, tar.stat().st_size)
            # start_new_session is NOT enough: the updater would still sit in this service's
            # cgroup, and systemd kills the whole cgroup on `systemctl restart repaper-dockd` —
            # taking the health check and rollback with it. A transient unit survives us.
            import os, shutil as _sh
            if sys.platform.startswith("linux") and _sh.which("systemd-run"):
                subprocess.Popen(["sudo", "-n", "systemd-run", "--collect", f"--unit=repaper-ota-{int(time.time())}",
                                  f"--uid={os.getuid()}", f"--gid={os.getgid()}",
                                  f"--setenv=HOME={os.path.expanduser('~')}",
                                  "/usr/bin/env", "bash", str(script), str(tar), version],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                subprocess.Popen(["/usr/bin/env", "bash", str(script), str(tar), version],
                                 start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception as e:
            self._updating, self.updating_version = False, None
            log.error("cloud: update %s failed before install: %s", version, e)
