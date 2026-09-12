"""Job spool: ~/.repaper/spool/<job>/ with meta.json + page-N.png (decoded source pages, not yet rendered for a sheet)."""
from __future__ import annotations
import json, time, uuid
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional
from PIL import Image
from .config import SPOOL, HOME

# A job being received/decoded (the slow part on a Pi) has no spool entry yet, so the
# printer process drops a marker here for the daemon to surface as a "receiving" state.
INCOMING = HOME / "incoming.json"


@dataclass
class Job:
    id: str
    name: str
    user: str
    pages: int
    created: float
    state: str = "pending"          # pending | printing | done | cancelled | failed
    printed: list[dict] = field(default_factory=list)   # [{page, sheet, at}]
    error: Optional[str] = None
    source: str = ""

    @property
    def dir(self) -> Path: return SPOOL / self.id

    def page_path(self, n: int) -> Path: return self.dir / f"page-{n}.png"

    def save(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / "meta.json").write_text(json.dumps(asdict(self), indent=2) + "\n")

    def next_page(self) -> Optional[int]:
        done = {p["page"] for p in self.printed}
        for n in range(1, self.pages + 1):
            if n not in done: return n
        return None


def create_job(pages: list[Image.Image], name: str, user: str, source: str = "") -> Job:
    job = Job(id=time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6], name=name or "Untitled", user=user or "",
              pages=len(pages), created=time.time(), source=source)
    job.dir.mkdir(parents=True, exist_ok=True)
    for i, img in enumerate(pages, 1): img.save(job.page_path(i))
    job.save(); return job


def mark_incoming(name: str, user: str, size: int) -> None:
    """Called right before the (possibly slow) decode, so the Dock can show it's working."""
    try: INCOMING.write_text(json.dumps({"name": name or "Untitled", "user": user or "", "bytes": int(size), "since": time.time()}))
    except Exception: pass


def clear_incoming() -> None:
    try: INCOMING.unlink()
    except FileNotFoundError: pass
    except Exception: pass


def read_incoming() -> Optional[dict]:
    """The job currently being received, or None. Stale markers (a crashed decode) are ignored."""
    try:
        if INCOMING.exists():
            d = json.loads(INCOMING.read_text())
            if time.time() - float(d.get("since", 0)) < 300: return d
            clear_incoming()
    except Exception: pass
    return None


def load_job(job_id: str) -> Job:
    d = json.loads((SPOOL / job_id / "meta.json").read_text()); return Job(**d)


def list_jobs(states: tuple[str, ...] = ("pending", "printing")) -> list[Job]:
    if not SPOOL.exists(): return []
    jobs = []
    for d in sorted(SPOOL.iterdir()):
        if (d / "meta.json").exists():
            j = load_job(d.name)
            if not states or j.state in states: jobs.append(j)
    return jobs
