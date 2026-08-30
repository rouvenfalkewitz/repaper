"""Manual identifier: a sheet id is 'tapped' from the web UI (POST /tap) or from the terminal."""
from __future__ import annotations
import queue
from typing import Optional
from .base import SheetIdentifier


class ManualIdentifier(SheetIdentifier):
    def __init__(self): self._q: "queue.Queue[str]" = queue.Queue()

    @property
    def id(self) -> str: return "manual"

    def tap(self, sheet_id: str) -> None: self._q.put(sheet_id)

    def wait_for_tap(self, timeout: float) -> Optional[str]:
        try: return self._q.get(timeout=timeout)
        except queue.Empty: return None
