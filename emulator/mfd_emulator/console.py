"""Interactive console — a keyboard-driven fault menu over the live event log.

Runs a background thread reading stdin lines and dispatches single-key commands onto the
asyncio loop. The event log already streams to stdout, so this only adds the command surface.
Headless use (no TTY / piped stdin) is fine: the reader thread simply idles.
"""

from __future__ import annotations

import asyncio
import sys
import threading

from .faults import FaultController

MENU = """
  ── MFD emulator — fault menu ───────────────────────────────
   r  toggle _rtsp._tcp advertising   (discovery timeout test)
   d  drop RTSP stream                (stream-starves test; endpoint stays up)
   s  resume RTSP stream
   c  close RRC control socket(s)     (transient drop; listener stays up)
   k  toggle RRC listener             (down = reconnects refused -> app gives up)
   t  toggle RRC read stall           (hung display: socket open, frames ignored)
   v  toggle RTSP endpoint            (video refused while control still works)
   n  NETWORK DOWN                    (everything at once: display vanishes)
   u  network up                      (the display comes back)
   i  status
   h  help (this menu)
   q  quit
  (also scriptable: scripts/fault.py, TCP 127.0.0.1:8571)
  ────────────────────────────────────────────────────────────
"""


class Console:
    def __init__(self, faults: FaultController, loop: asyncio.AbstractEventLoop,
                 stop_event: asyncio.Event) -> None:
        self._faults = faults
        self._loop = loop
        self._stop = stop_event
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        print(MENU, flush=True)
        self._thread = threading.Thread(target=self._read_loop, name="console", daemon=True)
        self._thread.start()

    def _read_loop(self) -> None:
        for raw in sys.stdin:
            cmd = raw.strip().lower()
            if not cmd:
                continue
            key = cmd[0]
            if key == "q":
                self._loop.call_soon_threadsafe(self._stop.set)
                return
            handler = {
                "r": self._faults.toggle_rtsp_discovery,
                "d": self._faults.drop_stream,
                "s": self._faults.resume_stream,
                "c": self._faults.close_rrc,
                "k": self._faults.toggle_rrc_listener,
                "t": self._faults.toggle_rrc_stall,
                "v": self._faults.toggle_video,
                "n": self._faults.network_down,
                "u": self._faults.network_up,
            }.get(key)
            if handler is not None:
                asyncio.run_coroutine_threadsafe(handler(), self._loop)
            elif key == "i":
                for line in self._faults.status_lines():
                    print("   " + line, flush=True)
            elif key == "h":
                print(MENU, flush=True)
            else:
                print(f"   unknown command: {cmd!r} (h for help)", flush=True)
        # stdin closed (EOF / headless): nothing more to read; emulator keeps running.
