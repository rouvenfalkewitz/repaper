"""repaper-dockd: watches the spool, waits for a tap, renders for the tapped sheet, prints via its transport.
Also serves a tiny local web UI (job inbox + manual tap) on http://localhost:<web_port>/."""
from __future__ import annotations
import io, json, logging, re, socket, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from PIL import Image
from . import __version__
from .config import load_config, ensure_home, SHEETS, HOME as HOME_PATH_
def HOME_PATH(): return HOME_PATH_
def SheetModel_copy(m):
    import copy; return copy.copy(m)
from .sheets import SheetRegistry, load_transports
from .identify import ManualIdentifier
from .render import render_for_sheet
from .spool import list_jobs, load_job, Job

log = logging.getLogger("repaper")


class Dock:
    def __init__(self):
        ensure_home(); self.cfg = load_config()
        self.registry = SheetRegistry(SHEETS)
        self.transports = load_transports(self.cfg, self.registry)
        self.identifier = ManualIdentifier()            # nfc-sticker / qr / ble-rssi plug in here later
        self.state = "ready"                            # ready | job-waiting | printing | printed | error  (the LED language)
        self.current: Job | None = None
        self.message = ""
        self.phase = ""                                 # live sub-status while printing ("connecting", "sending", ...)
        self._announced: set[str] = set()
        self.sheet_status: dict[str, dict] = {}         # sheet id → {battery_volts, temperature_c, online, seen, at}
        self._status_lock = threading.Lock()
        for sid in self.registry.ids():                 # last known readings survive a restart; "online" is unknown until the first scan
            sp = HOME_PATH() / "sheets" / sid / "status.json"
            if sp.exists():
                try: self.sheet_status[sid] = {**json.loads(sp.read_text()), "online": None, "at": 0}
                except Exception: pass
        self.hw_lock = threading.Lock()                 # one BLE/hardware operation at a time (print, status, add, test page)
        from .cloud import CloudAgent
        self.cloud = CloudAgent(self); self.cloud.start()
        threading.Thread(target=self._status_loop, daemon=True).start()

    # ── sheet status (battery/online) refreshed in the background while idle ──
    def _status_loop(self):
        while True:
            if self.state in ("ready", "job-waiting"):
                for sid in self.registry.ids():
                    if self.state == "printing": break
                    try:
                        ref, _ = self.registry.get(sid); tr = self.transports.get(ref.transport_id)
                        if not tr: continue
                        if not self.hw_lock.acquire(timeout=0.1): continue
                        try:
                            st = tr.status(ref)
                            if st.online and "hw" not in ref.keys and hasattr(tr, "hardware"):     # first sight: learn what it is
                                try: tr.hardware(ref)
                                except Exception as e: log.debug("hardware %s: %s", sid, e)
                        finally: self.hw_lock.release()
                        with self._status_lock:
                            prev = self.sheet_status.get(sid, {}); now = time.time()
                            # a passive scan can miss an advertisement — keep the last known readings and remember when it was last seen
                            self.sheet_status[sid] = {"battery_volts": st.battery_volts if st.online else prev.get("battery_volts"),
                                                      "temperature_c": st.temperature_c if st.online else prev.get("temperature_c"),
                                                      "online": st.online, "seen": now if st.online else prev.get("seen"), "at": now}
                        if st.online:
                            try:
                                d = HOME_PATH() / "sheets" / sid; d.mkdir(parents=True, exist_ok=True)
                                (d / "status.json").write_text(json.dumps({"battery_volts": st.battery_volts, "temperature_c": st.temperature_c, "seen": now}))
                            except Exception as e: log.debug("status save %s: %s", sid, e)
                    except Exception as e:
                        log.debug("status %s: %s", sid, e)
            time.sleep(self.cfg.get("status_refresh_seconds", 60))

    # ── the loop: one job at a time, one page per tap ─────────────────────────
    def run(self):
        log.info("dock ready · transports: %s · sheets: %s", list(self.transports), self.registry.ids())
        while True:
            pending = [j for j in list_jobs() if j.state in ("pending", "printing")]
            if not pending:
                self.state, self.current = "ready", None; time.sleep(0.5); continue
            job = pending[0]; self.current = job
            if time.time() - job.created > self.cfg["job_timeout_seconds"]:
                job.state = "cancelled"; job.error = "nobody held a sheet in time"; job.save()
                log.info("job %s expired", job.id); continue
            if self.state == "error": self.message = getattr(self, "last_error", "")   # keep a failure visible until the next tap
            elif self.state != "job-waiting": self.message = ""                        # a previous success never lingers under a new job
            self.state = "job-waiting"; page_no = job.next_page(); self.phase = ""
            key = f"{job.id}:{page_no}"
            if key not in self._announced:
                log.info("job %s (%s) page %d/%d waiting — hold a sheet", job.id, job.name, page_no, job.pages); self._announced.add(key)
            sheet_id = self.identifier.wait_for_tap(timeout=2.0)
            if not sheet_id: continue
            self.print_page(job, page_no, sheet_id)

    def print_page(self, job: Job, page_no: int, sheet_id: str):
        try:
            ref, model = self.registry.get(sheet_id)
        except KeyError:
            self.message = f"unknown sheet {sheet_id}"; self.state = "error"; return
        transport = self.transports.get(ref.transport_id)
        if not transport:
            self.message = f"transport {ref.transport_id} not loaded"; self.state = "error"; return
        self.state = "printing"; job.state = "printing"; job.save(); self.phase = "rendering"; self.message = ""; self.last_error = ""; self.printing_since = time.time()
        src = Image.open(job.page_path(page_no))
        page = render_for_sheet(src, transport.describe(ref))
        def progress(text):
            self.phase = text; log.info("  … %s", text)
        t0 = time.time()
        with self.hw_lock: res = transport.print(ref, page, progress=progress)
        if res.ok:
            try:                                                        # remember what is on the sheet now
                d = HOME_PATH() / "sheets" / sheet_id; d.mkdir(parents=True, exist_ok=True)
                page.image.convert("RGB").save(d / "last.png")
                (d / "last.json").write_text(json.dumps({"job": job.name, "page": page_no, "at": time.time()}))
            except Exception as e: log.debug("last image: %s", e)
            job.printed.append({"page": page_no, "sheet": sheet_id, "at": time.time()})
            if job.next_page() is None: job.state = "done"
            job.save(); self.state = "printed"; self.message = f"printed page {page_no} of {job.name} on {ref.name or sheet_id} ({time.time()-t0:.1f}s) {res.message}"
            log.info(self.message); time.sleep(3 if job.state == "done" else 1)
        else:
            job.state = "pending"; job.save(); self.state = "error"; self.message = res.message; self.last_error = res.message; log.error("print failed: %s", res.message); time.sleep(2)

    # ── settings & sheet management (used by /settings) ──────────────────────
    def settings(self) -> dict:
        import platform
        ips = []
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET): ips.append(info[4][0])
        except Exception: pass
        sheets = {k: {"name": e["name"], "serial": e.get("serial"), "transport": e["transport"], "address": e["address"],
                      "size": f'{e["model"]["width"]}×{e["model"]["height"]} {e["model"]["palette"]}', "inset": list(e["model"].get("inset", (0, 0, 0, 0))),
                      "hw": e.get("keys", {}).get("hw", {})}
                  for k, e in self.registry.all().items()}
        return {"printer_name": self.cfg["printer_name"], "job_timeout_seconds": self.cfg["job_timeout_seconds"], "notifications": self.notifications(self.snapshot()["sheets"]),
                "address": f"http://{socket.gethostname()}:{self.cfg['web_port']}/", "sheets": sheets, "cloud": self.cloud.info(),
                "network": {"hostname": socket.gethostname(), "addresses": ", ".join(sorted(set(ips))) or "—", "dock page": f"http://{socket.gethostname()}:{self.cfg['web_port']}/",
                            "printer": "advertised via DNS-SD (AirPrint, IPP Everywhere)"},
                "about": {"software": f"RePaper Dock {__version__}", "system": f"{platform.system()} {platform.machine()}",
                          "transports": ", ".join(self.transports), "identifier": self.identifier.id, "state": str(HOME_PATH())}}

    def notifications(self, sheets: dict) -> list[dict]:
        """Things a person should know about this Dock. Shown in Settings; counted on the gear."""
        out = []
        if self.identifier.id == "manual":
            out.append({"level": "warn", "title": "No sheet reader on this Dock", "text": "Sheets are chosen on the page and printed with the Print button. With a reader, tapping a sheet does this automatically."})
        for k, v in sheets.items():
            mv = v.get("battery_volts"); lim = v.get("min_battery_mv") or 2700
            if mv is not None and mv * 1000 < lim:
                out.append({"level": "err", "title": f"{v['name'] or k}: battery low ({mv:.2f} V)", "text": "The Dock will not print to it until the cell is replaced — a refresh on a weak cell can leave the sheet half-drawn."})
            elif v.get("online") is False and (v.get("seen") is None or time.time() - v["seen"] > 600):
                out.append({"level": "info", "title": f"{v['name'] or k}: not in range", "text": "The sheet has not been heard nearby for a while. Bring it closer or check its battery."})
        return out

    def save_settings(self, data: dict) -> None:
        from .config import CONFIG
        allowed = {"printer_name": str, "job_timeout_seconds": int, "status_refresh_seconds": int}
        if "printer_name" in data and not (1 <= len(str(data["printer_name"]).strip()) <= 63): raise ValueError("printer name must be 1–63 characters")
        for k, typ in allowed.items():
            if k in data and data[k] is not None:
                v = typ(data[k])
                if k != "printer_name" and v < 30: raise ValueError(f"{k} must be at least 30")
                self.cfg[k] = v.strip() if isinstance(v, str) else v
        if "cloud_url" in data:
            u = str(data["cloud_url"] or "").strip()
            if u and not u.startswith(("ws://", "wss://")): raise ValueError("the cloud address must start with wss:// (or ws:// for testing)")
            self.cfg["cloud_url"] = u
        cur = json.loads(CONFIG.read_text()) if CONFIG.exists() else {}
        cur.update({k: self.cfg[k] for k in (*allowed, "cloud_url") if k in self.cfg}); CONFIG.write_text(json.dumps(cur, indent=2) + "\n")

    def add_sheet(self, text: str, name: str = "", serial: str = "") -> dict:
        """text = QR landing URL, OD name, BLE address, or 'WxH' for the mock transport. Reads size/colours from the sheet."""
        from .sheets import SheetRef, SheetModel
        from .sheets.opendisplay_ble import parse_landing_url, _is_mac
        text = text.strip(); keys = {}; transport = "opendisplay-ble"; address = text
        if text.startswith("http"):
            info = parse_landing_url(text); address = info["name"]; keys = {"key": info["key"]} if info["key"] else {}
        elif re.fullmatch(r"\d+x\d+", text.lower()): transport = "mock"
        elif not (text.upper().startswith("OD") or _is_mac(text)): raise ValueError("paste the sheet's QR link, its OD name (OD…), or a Bluetooth address")
        if transport not in self.transports: raise ValueError(f"transport {transport} is not enabled")
        if self.registry.find_by_address(transport, address): raise ValueError("this sheet is already added")
        ref = SheetRef(transport, address, keys, name or address); tr = self.transports[transport]; serial = (serial or "").strip().upper() or None
        if transport == "mock":
            w, h = (int(v) for v in text.lower().split("x")); model = SheetModel(w, h, "BWR")
        else:
            if self.state == "printing": raise ValueError("the Dock is printing — try again in a moment")
            log.info("add sheet: %s via %s (%s)", address, transport, "with key" if keys.get("key") else "no key")
            try:
                with self.hw_lock: model = tr.describe(ref)
            except Exception as e:
                if "encryption key" in str(e) or type(e).__name__ == "AuthenticationRequiredError":
                    raise ValueError(f"{address} is locked. Its key travels only in the QR code printed on the label — scan that QR and paste the whole link; the name alone can't unlock it.") from e
                if "timed out" in str(e).lower() or "not found" in str(e).lower() or "disappeared" in str(e).lower():
                    raise ValueError("could not reach the sheet — bring it within arm's reach of the Dock and try again; these labels only speak up every few seconds, so a second try often lands") from e
                raise ValueError(f"could not read the sheet: {e}") from e
        base = re.sub(r"[^a-z0-9]+", "-", (name or address).lower()).strip("-") or "sheet"; sid = base; n = 2
        while sid in self.registry.ids(): sid = f"{base}-{n}"; n += 1
        self.registry.add(sid, ref, model, serial=serial)
        return {"id": sid, "size": f"{model.width}×{model.height} {model.palette}"}

    def update_sheet(self, sid: str, data: dict) -> None:
        ref, model = self.registry.get(sid)
        if "name" in data: ref.name = str(data["name"]).strip() or ref.name
        serial = self.registry.all()[sid].get("serial")
        if "serial" in data: serial = re.sub(r"[^A-Za-z0-9-]", "", str(data["serial"])).upper() or None
        if "inset" in data:
            vals = [int(v) for v in re.split(r"[,\s]+", str(data["inset"]).strip()) if v != ""]
            if len(vals) != 4 or min(vals) < 0 or vals[0] + vals[2] >= model.width or vals[1] + vals[3] >= model.height: raise ValueError("inset must be four numbers: left, top, right, bottom")
            model.inset = tuple(vals)
        self.registry.add(sid, ref, model, serial=serial)

    def remove_sheet(self, sid: str) -> None:
        d = self.registry.all(); d.pop(sid); self.registry._data = d; self.registry.save()
        with self._status_lock: self.sheet_status.pop(sid, None)

    def sheet_action(self, sid: str, what: str) -> str:
        from .render import render_for_sheet
        from .testpage import test_page, calibration_page
        if self.state == "printing": raise ValueError("the Dock is printing — try again in a moment")
        ref, model = self.registry.get(sid); tr = self.transports[ref.transport_id]
        if what == "calibrate":
            m = SheetModel_copy(model); m.inset = (0, 0, 0, 0); page = render_for_sheet(calibration_page(m), m, dither=False, auto_rotate=False, trim=False)
        else:
            page = render_for_sheet(test_page(model), model)
        with self.hw_lock: res = tr.print(ref, page)
        if not res.ok: raise ValueError(res.message)
        return f"Printed on {ref.name or sid} ({res.seconds:.0f} s)"

    def discover(self) -> list[dict]:
        if self.state == "printing": raise ValueError("the Dock is printing — try again in a moment")
        out = []
        with self.hw_lock:
            for tid, tr in self.transports.items():
                for ref in tr.discover(timeout=8.0):
                    out.append({"transport": tid, "address": ref.address, "name": ref.name, "registered": self.registry.find_by_address(tid, ref.address)})
        return out

    # ── read-only view for the UI ────────────────────────────────────────────
    def snapshot(self) -> dict:
        with self._status_lock: st = dict(self.sheet_status)
        sheets = {}
        for k, e in self.registry.all().items():
            tr = self.transports.get(e["transport"]); caps = tr.capabilities() if tr else {}
            m = e["model"]; last = None
            lp = HOME_PATH() / "sheets" / k / "last.json"
            if lp.exists():
                try: last = json.loads(lp.read_text())
                except Exception: last = None
            sheets[k] = {"name": e["name"], "serial": e.get("serial"), "transport": e["transport"], "address": e["address"],
                         "width": m["width"], "height": m["height"], "palette": m["palette"], "inset": list(m.get("inset", (0, 0, 0, 0))),
                         "size": f'{m["width"]}×{m["height"]} {m["palette"]}', "hw": e.get("keys", {}).get("hw", {}),
                         "min_battery_mv": caps.get("min_battery_mv"), "last": last, **st.get(k, {})}
        return {"state": self.state, "phase": self.phase, "message": self.message, "error": (self.message if self.state == "error" else getattr(self, "last_error", "")) or "",
                "printer": self.cfg["printer_name"], "notifications": self.notifications(sheets),
                "identifier": self.identifier.id, "version": __version__, "address": f"http://{socket.gethostname()}:{self.cfg['web_port']}/",
                "job": None if not self.current else {"id": self.current.id, "name": self.current.name, "pages": self.current.pages, "user": self.current.user,
                                                      "next_page": self.current.next_page(), "state": self.current.state,
                                                      "printed": len(self.current.printed), "created": self.current.created},
                "now": time.time(), "printing_since": getattr(self, "printing_since", None),
                "sheets": sheets,
                "recent": [{"id": j.id, "name": j.name, "state": j.state, "pages": j.pages, "user": j.user, "created": j.created,
                            "printed": [{"page": x["page"], "sheet": (self.registry.all().get(x["sheet"], {}) or {}).get("name") or x["sheet"], "at": x["at"]} for x in j.printed],
                            "error": j.error} for j in list_jobs(("done", "cancelled", "failed"))[-20:]]}



UI_DIR = Path(__file__).resolve().parent / "ui"
STATIC = {"/": ("index.html", "text/html; charset=utf-8"), "/settings": ("settings.html", "text/html; charset=utf-8"),
          "/tokens.css": ("tokens.css", "text/css"), "/app.css": ("app.css", "text/css"), "/favicon.svg": ("favicon.svg", "image/svg+xml")}


def make_handler(dock: Dock):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def _send(self, body: bytes, ctype="text/html; charset=utf-8", code=200, cache="no-store"):
            self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", cache); self.end_headers(); self.wfile.write(body)
        def _json(self, obj, code=200): self._send(json.dumps(obj).encode(), "application/json", code)
        def do_GET(self):
            u = urlparse(self.path)
            if u.path in STATIC:
                fn, ct = STATIC[u.path]; return self._send((UI_DIR / fn).read_bytes(), ct, cache="no-store")   # tiny files; never let a browser hold an old stylesheet
            if u.path == "/api/status": return self._json(dock.snapshot())
            if u.path == "/api/settings": return self._json(dock.settings())
            if u.path == "/api/discover":
                try: return self._json({"found": dock.discover()})
                except Exception as e: return self._json({"error": str(e)}, 400)
            if u.path.startswith("/sheet-image/"):
                sid = u.path.split("/")[2]; fp = HOME_PATH() / "sheets" / sid / "last.png"
                if fp.exists(): return self._send(fp.read_bytes(), "image/png", cache="no-store")
                return self._send(b"none", "text/plain", 404)
            if u.path.startswith("/preview/"):
                try:
                    _, _, job_id, n = u.path.split("/")
                    img = Image.open(load_job(job_id).page_path(int(n))); img.thumbnail((800, 800)); b = io.BytesIO(); img.save(b, "PNG")
                    return self._send(b.getvalue(), "image/png", cache="max-age=60")
                except Exception: return self._send(b"not found", "text/plain", 404)
            self._send(b"not found", "text/plain", 404)
        def do_POST(self):
            u = urlparse(self.path); n = int(self.headers.get("Content-Length", 0)); raw = self.rfile.read(n)
            if (self.headers.get("Content-Type") or "").startswith("application/json"):
                try:
                    data = json.loads(raw or b"{}")
                    if u.path == "/api/settings": dock.save_settings(data); return self._json({"ok": True})
                    if u.path == "/api/sheets/add": return self._json(dock.add_sheet(data.get("input", ""), data.get("name", ""), data.get("serial", "")))
                    m = re.fullmatch(r"/api/sheets/([A-Za-z0-9_.-]+)(?:/(remove|test-page|calibrate))?", u.path)
                    if m:
                        sid, what = m.group(1), m.group(2)
                        if sid not in dock.registry.ids(): return self._json({"error": "unknown sheet"}, 404)
                        if what == "remove": dock.remove_sheet(sid); return self._json({"ok": True})
                        if what: return self._json({"ok": True, "message": dock.sheet_action(sid, what)})
                        dock.update_sheet(sid, data); return self._json({"ok": True})
                    return self._json({"error": "unknown endpoint"}, 404)
                except (ValueError, KeyError) as e: return self._json({"error": str(e)}, 400)
                except Exception as e: log.exception("api"); return self._json({"error": f"{type(e).__name__}: {e}"}, 500)
            form = parse_qs(raw.decode())
            if u.path == "/tap" and form.get("sheet"): dock.identifier.tap(form["sheet"][0])
            elif u.path == "/cancel" and form.get("job"):
                j = load_job(form["job"][0]); j.state = "cancelled"; j.save(); dock.message = ""
            self.send_response(303); self.send_header("Location", "/"); self.end_headers()
    return H


class _Server(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True
    def server_bind(self):
        # HTTPServer.server_bind() calls socket.getfqdn() on the bind address — a reverse-DNS lookup that
        # stalls ~30 s on macOS for 0.0.0.0. We don't need the FQDN.
        import socketserver
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = "repaper-dock", self.server_address[1]


def serve(dock: Dock):
    bind = dock.cfg.get("web_bind", "0.0.0.0")
    try:
        srv = _Server((bind, dock.cfg["web_port"]), make_handler(dock))
    except OSError as e:
        raise SystemExit(f"web UI port {dock.cfg['web_port']} is in use — another repaper-dockd is running? (pkill -f 'repaper-dockd run')  [{e}]")
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    log.info("web UI: http://localhost:%d/  (on the network: http://%s:%d/)", dock.cfg["web_port"], socket.gethostname(), dock.cfg["web_port"])
