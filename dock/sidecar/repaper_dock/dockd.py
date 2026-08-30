"""repaper-dockd: watches the spool, waits for a tap, renders for the tapped sheet, prints via its transport.
Also serves a tiny local web UI (job inbox + manual tap) on http://localhost:<web_port>/."""
from __future__ import annotations
import io, json, logging, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from PIL import Image
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
        return {"state": self.state, "phase": self.phase, "message": self.message, "printer": self.cfg["printer_name"], "identifier": self.identifier.id,
                "job": None if not self.current else {"id": self.current.id, "name": self.current.name, "pages": self.current.pages,
                                                      "next_page": self.current.next_page(), "state": self.current.state},
                "sheets": {k: {"name": e["name"], "transport": e["transport"], "size": f'{e["model"]["width"]}×{e["model"]["height"]} {e["model"]["palette"]}'}
                           for k, e in self.registry.all().items()},
                "recent": [{"id": j.id, "name": j.name, "state": j.state, "pages": j.pages} for j in list_jobs(("done", "cancelled", "failed"))[-5:]]}


HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>RePaper Dock</title><link rel="stylesheet" href="/tokens.css">
<style>body{font-family:var(--font-body);background:var(--bg);color:var(--text);margin:0}.wrap{max-width:720px;margin:0 auto;padding:32px 20px}
h1{font-family:var(--font-display);font-stretch:125%;text-transform:uppercase;letter-spacing:-.02em;font-size:1.25rem;margin:0 0 4px}
.card{background:var(--surface);border:1px solid var(--border);border-radius:var(--r-card);padding:20px;margin-top:16px}
.pill{display:inline-flex;align-items:center;gap:7px;font-family:var(--font-display);font-stretch:112%;font-size:.6875rem;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:4px 10px;border-radius:999px;background:var(--accent-tint);color:var(--accent-text)}
.pill::before{content:"";width:7px;height:7px;border-radius:50%;background:currentColor}.blink::before{animation:b 1s steps(1) infinite}@keyframes b{50%{opacity:0}}
img.prev{background:#fff;border:1px solid var(--border-strong);border-radius:4px;max-width:100%;display:block;margin:12px 0}
button{font:600 .9375rem var(--font-body);padding:10px 16px;border-radius:var(--r-control);border:1px solid transparent;background:var(--accent);color:var(--on-accent);cursor:pointer;box-shadow:var(--glow)}
select{font:inherit;padding:9px 12px;border-radius:var(--r-control);border:1px solid var(--border-strong);background:var(--bg);color:var(--text)}
.muted{color:var(--text-2);font-size:.875rem}.mono{font-family:var(--font-mono);font-size:.8125rem;color:var(--text-3)}</style></head><body><div class="wrap">
<h1>{{printer}}</h1><div class="muted">Dock · {{state}}</div>
<div class="card" id="job">{{job_html}}</div>
<div class="card"><div class="muted">Sheets known to this Dock</div><ul class="mono">{{sheets_html}}</ul><div class="muted" id="msg">{{message}}</div></div>
</div><script>
// poll quietly; reload only when the job/state actually changes (no flicker)
let sig = "{{sig}}";
async function tick(){ try{ const r=await fetch("/api/status"); const s=await r.json();
  const now = [s.state, s.job && s.job.id, s.job && s.job.next_page, s.job && s.job.state].join("|");
  const ph = document.getElementById("phase"); if (ph) ph.textContent = s.phase ? "… " + s.phase : "";
  const m = document.getElementById("msg"); if (m) m.textContent = s.message || "";
  if (now !== sig) location.reload(); } catch(e){} }
setInterval(tick, 1000);
</script></body></html>"""


def make_handler(dock: Dock):
    tokens_css = None
    try:
        from pathlib import Path
        p = Path(__file__).resolve().parents[3] / "brand" / "tokens.css"
        tokens_css = p.read_text() if p.exists() else ""
    except Exception: tokens_css = ""

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def _send(self, body: bytes, ctype="text/html; charset=utf-8", code=200):
            self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            u = urlparse(self.path)
            if u.path == "/tokens.css": return self._send(tokens_css.encode(), "text/css")
            if u.path == "/api/status": return self._send(json.dumps(dock.snapshot()).encode(), "application/json")
            if u.path.startswith("/preview/"):
                _, _, job_id, n = u.path.split("/")
                try:
                    img = Image.open(load_job(job_id).page_path(int(n))); img.thumbnail((640, 640)); b = io.BytesIO(); img.save(b, "PNG")
                    return self._send(b.getvalue(), "image/png")
                except Exception: return self._send(b"not found", "text/plain", 404)
            s = dock.snapshot()
            if s["job"] and s["state"] == "printing":
                j = s["job"]
                job_html = (f'<span class="pill">Printing</span><h2 style="margin:10px 0 0">{j["name"]}</h2><div class="muted">page {j["next_page"] or j["pages"]} of {j["pages"]}</div>'
                            f'<div class="muted" id="phase" style="margin-top:8px">… {s["phase"]}</div>'
                            f'<img class="prev" src="/preview/{j["id"]}/{j["next_page"] or j["pages"]}" alt="">')
            elif s["job"]:
                j = s["job"]; opts = "".join(f'<option value="{k}">{v["name"] or k} · {v["size"]}</option>' for k, v in s["sheets"].items())
                hint = ("This Mac has no NFC reader, so the button stands in for holding the sheet to the Dock: pick the sheet, press the button."
                        if s["identifier"] == "manual" else "Hold a sheet to the Dock.")
                job_html = (f'<span class="pill blink">Waiting for sheet</span><h2 style="margin:10px 0 0">{j["name"]}</h2><div class="muted">page {j["next_page"]} of {j["pages"]}</div>'
                            f'<img class="prev" src="/preview/{j["id"]}/{j["next_page"]}" alt="">'
                            f'<div class="muted" style="margin:0 0 10px">{hint}</div>'
                            f'<form method="post" action="/tap"><select name="sheet">{opts}</select> <button type="submit">Hold this sheet to the Dock</button></form>'
                            f'<form method="post" action="/cancel" style="margin-top:8px"><input type="hidden" name="job" value="{j["id"]}"><button type="submit" style="background:transparent;color:var(--red);border-color:var(--red);box-shadow:none">Cancel job</button></form>')
            else:
                job_html = f'<span class="pill">Ready</span><h2 style="margin:10px 0 0">Nothing to print</h2><div class="muted">Print to “{s["printer"]}” from any device — it shows up here.</div>'
            sheets_html = "".join(f'<li>{k} — {v["name"] or ""} · {v["transport"]} · {v["size"]}</li>' for k, v in s["sheets"].items()) or "<li>none yet — run: repaper-dockd add-sheet …</li>"
            j = s["job"]; sig = "|".join(str(x) for x in (s["state"], j and j["id"], j and j["next_page"], j and j["state"]))
            page = HTML
            for k, v in {"printer": s["printer"], "state": s["state"], "job_html": job_html, "sheets_html": sheets_html, "message": s["message"], "sig": sig}.items():
                page = page.replace("{{" + k + "}}", str(v))
            self._send(page.encode())
        def do_POST(self):
            u = urlparse(self.path); n = int(self.headers.get("Content-Length", 0)); form = parse_qs(self.rfile.read(n).decode())
            if u.path == "/tap" and form.get("sheet"): dock.identifier.tap(form["sheet"][0])
            if u.path == "/cancel" and form.get("job"):
                j = load_job(form["job"][0]); j.state = "cancelled"; j.save()
            self.send_response(303); self.send_header("Location", "/"); self.end_headers()
    return H


def serve(dock: Dock):
    ThreadingHTTPServer.allow_reuse_address = True
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", dock.cfg["web_port"]), make_handler(dock))
    except OSError as e:
        raise SystemExit(f"web UI port {dock.cfg['web_port']} is in use — another repaper-dockd is running? (pkill -f 'repaper-dockd run')  [{e}]")
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    log.info("web UI: http://localhost:%d/", dock.cfg["web_port"])
