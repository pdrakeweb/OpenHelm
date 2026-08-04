"""Fault control server — the console's commands, scriptable over TCP.

The interactive console needs a TTY; automated resilience tests need to trigger faults from a
script while the emulator runs in the background. This is a line protocol on localhost:

    $ python scripts/fault.py net-down
    ok

One command per line; replies are ``ok``, ``err <reason>``, or (for ``status``/``log``) data
lines followed by ``ok``. Localhost-only by design: it is a test rig's kill switch, not part of
the simulated MFD, and nothing on the LAN should be able to reach it.
"""

from __future__ import annotations

import asyncio
from typing import Awaitable, Callable

from .events import EventLog
from .faults import FaultController


class ControlServer:
    def __init__(self, faults: FaultController, log: EventLog, port: int) -> None:
        self._faults = faults
        self._log = log
        self._port = port
        self._server: asyncio.AbstractServer | None = None
        # Command table. Names are stable: scripts and CI depend on them.
        self._commands: dict[str, Callable[[], Awaitable[None]]] = {
            "close-rrc": faults.close_rrc,
            "rrc-down": faults.rrc_down,
            "rrc-up": faults.rrc_up,
            "stall-on": lambda: faults.rrc_stall(True),
            "stall-off": lambda: faults.rrc_stall(False),
            "stream-drop": faults.drop_stream,
            "stream-resume": faults.resume_stream,
            "video-down": faults.video_down,
            "video-up": faults.video_up,
            "net-down": faults.network_down,
            "net-up": faults.network_up,
            "discovery-toggle": faults.toggle_rtsp_discovery,
        }

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._handle, host="127.0.0.1", port=self._port)
        self._log.sys(f"fault control listening on 127.0.0.1:{self._port} "
                      f"(scripts/fault.py; commands: status, log [n], {', '.join(self._commands)})")

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while True:
                raw = await reader.readline()
                if not raw:
                    break
                parts = raw.decode("utf-8", errors="replace").strip().lower().split()
                if not parts:
                    continue
                cmd, args = parts[0], parts[1:]
                try:
                    if cmd == "status":
                        for line in self._faults.status_lines():
                            writer.write((line + "\n").encode())
                        writer.write(b"ok\n")
                    elif cmd == "log":
                        n = int(args[0]) if args else 20
                        for line in self._log.recent(n):
                            writer.write((line + "\n").encode())
                        writer.write(b"ok\n")
                    elif cmd in self._commands:
                        await self._commands[cmd]()
                        writer.write(b"ok\n")
                    else:
                        writer.write(f"err unknown command {cmd!r}\n".encode())
                except Exception as e:  # a failed fault must report, never kill the server
                    writer.write(f"err {e!r}\n".encode())
                await writer.drain()
        except (ConnectionResetError, asyncio.CancelledError):
            pass
        finally:
            try:
                writer.close()
            except Exception:
                pass

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
