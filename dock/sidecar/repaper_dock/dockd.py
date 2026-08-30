"""repaper-dockd: watches the spool, waits for a tap, renders for the tapped sheet, prints via its transport.
Also serves a tiny local web UI (job inbox + manual tap) on http://localhost:<web_port>/."""
from __future__ import annotations
import io, json, logging, socket, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from PIL import Image
from . import __version__
from .config import load_config, ensure_home, SHEETS
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
        self.sheet_status: dict[str, dict] = {}         # sheet id → {battery_volts, temperature_c, online, at}
        self._status_lock = threading.Lock()
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
                        st = tr.status(ref)
                        with self._status_lock:
                            self.sheet_status[sid] = {"battery_volts": st.battery_volts, "temperature_c": st.temperature_c,
                                                      "online": st.online, "at": time.time()}
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
        self.state = "printing"; job.state = "printing"; job.save(); self.phase = "rendering"; self.message = ""
        src = Image.open(job.page_path(page_no))
        page = render_for_sheet(src, transport.describe(ref))
        def progress(text):
            self.phase = text; log.info("  … %s", text)
        t0 = time.time(); res = transport.print(ref, page, progress=progress)
        if res.ok:
            job.printed.append({"page": page_no, "sheet": sheet_id, "at": time.time()})
            if job.next_page() is None: job.state = "done"
            job.save(); self.state = "printed"; self.message = f"printed page {page_no} of {job.name} on {ref.name or sheet_id} ({time.time()-t0:.1f}s) {res.message}"
            log.info(self.message); time.sleep(3 if job.state == "done" else 1)
        else:
            job.state = "pending"; job.save(); self.state = "error"; self.message = res.message; log.error("print failed: %s", res.message); time.sleep(2)

    # ── read-only view for the UI ────────────────────────────────────────────
    def snapshot(self) -> dict:
        with self._status_lock: st = dict(self.sheet_status)
        sheets = {}
        for k, e in self.registry.all().items():
            tr = self.transports.get(e["transport"]); caps = tr.capabilities() if tr else {}
            sheets[k] = {"name": e["name"], "transport": e["transport"],
                         "size": f'{e["model"]["width"]}×{e["model"]["height"]} {e["model"]["palette"]}',
                         "min_battery_mv": caps.get("min_battery_mv"), **st.get(k, {})}
        return {"state": self.state, "phase": self.phase, "message": self.message, "printer": self.cfg["printer_name"],
                "identifier": self.identifier.id, "version": __version__, "address": f"http://{socket.gethostname()}:{self.cfg['web_port']}/",
                "job": None if not self.current else {"id": self.current.id, "name": self.current.name, "pages": self.current.pages, "user": self.current.user,
                                                      "next_page": self.current.next_page(), "state": self.current.state},
                "sheets": sheets,
                "recent": [{"id": j.id, "name": j.name, "state": j.state, "pages": j.pages} for j in list_jobs(("done", "cancelled", "failed"))[-8:]]}



UI_DIR = Path(__file__).resolve().parent / "ui"
INDEX = (UI_DIR / "index.html").read_text()
TOKENS = (UI_DIR / "tokens.css").read_text()


def make_handler(dock: Dock):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def _send(self, body: bytes, ctype="text/html; charset=utf-8", code=200, cache="no-store"):
            self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", cache); self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            u = urlparse(self.path)
            if u.path == "/": return self._send(INDEX.encode())
            if u.path == "/tokens.css": return self._send(TOKENS.encode(), "text/css", cache="max-age=3600")
            if u.path == "/api/status": return self._send(json.dumps(dock.snapshot()).encode(), "application/json")
            if u.path.startswith("/preview/"):
                try:
                    _, _, job_id, n = u.path.split("/")
                    img = Image.open(load_job(job_id).page_path(int(n))); img.thumbnail((800, 800)); b = io.BytesIO(); img.save(b, "PNG")
                    return self._send(b.getvalue(), "image/png", cache="max-age=60")
                except Exception: return self._send(b"not found", "text/plain", 404)
            self._send(b"not found", "text/plain", 404)
        def do_POST(self):
            u = urlparse(self.path); n = int(self.headers.get("Content-Length", 0)); form = parse_qs(self.rfile.read(n).decode())
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
