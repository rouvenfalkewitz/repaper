"""Job spool: ~/.repaper/spool/<job>/ with meta.json + page-N.png (decoded source pages, not yet rendered for a sheet)."""
from __future__ import annotations
import json, time, uuid
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional
from PIL import Image
from .config import SPOOL


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
