"""Event log — the emulator's test oracle.

Every decoded control message and every lifecycle/fault transition becomes a timestamped,
symbolic line here. This log *is* how we verify the app sent the right bytes for each
on-screen gesture, without a real MFD.
"""

from __future__ import annotations

import sys
from collections import deque
from datetime import datetime
from typing import Callable, Deque


class EventLog:
    def __init__(self, file_path: str | None = None, keep: int = 500) -> None:
        # Windows consoles default to cp1252, which mangles the degree sign etc. Force UTF-8.
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
        except (AttributeError, ValueError):
            pass
        self._file = open(file_path, "a", encoding="utf-8", buffering=1) if file_path else None
        self._recent: Deque[str] = deque(maxlen=keep)
        self._subscribers: list[Callable[[str], None]] = []

    def subscribe(self, fn: Callable[[str], None]) -> None:
        """Register a sink (e.g. a live console) that receives each formatted line."""
        self._subscribers.append(fn)

    def recent(self, n: int | None = None) -> list[str]:
        lines = list(self._recent)
        return lines if n is None else lines[-n:]

    def log(self, channel: str, message: str) -> None:
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        line = f"{ts}  {channel:<4}  {message}"
        self._recent.append(line)
        print(line, file=sys.stdout, flush=True)
        if self._file:
            self._file.write(line + "\n")
        for fn in self._subscribers:
            try:
                fn(line)
            except Exception:  # a broken sink must never take down the emulator
                pass

    # Convenience channels ---------------------------------------------------

    def rrc(self, message: str) -> None:
        self.log("RRC", message)

    def rtsp(self, message: str) -> None:
        self.log("RTSP", message)

    def mdns(self, message: str) -> None:
        self.log("mDNS", message)

    def sys(self, message: str) -> None:
        self.log("SYS", message)

    def fault(self, message: str) -> None:
        self.log("FALT", message)

    def close(self) -> None:
        if self._file:
            self._file.close()
            self._file = None
