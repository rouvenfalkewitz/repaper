"""How the Dock learns WHICH sheet is being held to it. Separate from how it talks to sheets."""
from __future__ import annotations
from abc import ABC, abstractmethod
from typing import Optional


class SheetIdentifier(ABC):
    @property
    @abstractmethod
    def id(self) -> str: ...

    @abstractmethod
    def wait_for_tap(self, timeout: float) -> Optional[str]:
        """Block until a sheet is presented; return its sheet id, or None on timeout."""
