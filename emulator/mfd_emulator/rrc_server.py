"""RRC control-channel TCP server.

Accepts the app's single control socket, holds it open (the app keeps it alive with
keepAlive+tcpNoDelay and only reads it to detect EOF), and decodes every inbound frame to a
symbolic oracle line. We never send anything back — the real MFD doesn't need to for the app
to function, and the app never parses inbound RRC bytes.

Closing a client socket is exposed as a fault (``close_clients``): it makes the app's EOF
watcher tear down the remote-control screen, which is exactly the error path we want to test.
"""

from __future__ import annotations

import asyncio
import socket

from .events import EventLog
from .rrc_codec import RrcFrameParser


class RrcServer:
    def __init__(self, host: str, port: int, log: EventLog) -> None:
        self._host = host
        self._port = port
        self._log = log
        self._server: asyncio.AbstractServer | None = None
        self._clients: set[asyncio.StreamWriter] = set()
        # Stall fault: while cleared, client handlers stop reading. The app's writes then land in
        # kernel/StreamReader buffers and go nowhere — the half-open / hung-peer shape, where the
        # TCP connection is "up" but the display has stopped servicing it.
        self._stall_gate = asyncio.Event()
        self._stall_gate.set()

    async def start(self) -> None:
        if self._server is not None:
            return
        # Bind on all interfaces so the guest reaches us via the advertised LAN IP.
        self._server = await asyncio.start_server(self._handle_client, host="0.0.0.0", port=self._port)
        self._log.sys(f"RRC control server listening on tcp:{self._port}")

    @property
    def listening(self) -> bool:
        return self._server is not None

    @property
    def stalled(self) -> bool:
        return not self._stall_gate.is_set()

    async def _handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        self._tune_socket(writer)
        self._clients.add(writer)
        self._log.rrc(f"client connected from {peer[0]}:{peer[1]}")
        parser = RrcFrameParser()
        try:
            while True:
                await self._stall_gate.wait()  # parked here while the stall fault is active
                data = await reader.read(4096)
                if not data:
                    break  # app closed the socket (EOF)
                for event in parser.feed(data):
                    self._log.rrc(str(event))
        except (ConnectionResetError, asyncio.CancelledError):
            pass
        except Exception as e:  # never let one client kill the server
            self._log.rrc(f"client error: {e!r}")
        finally:
            self._clients.discard(writer)
            self._log.rrc(f"client disconnected ({peer[0]}:{peer[1]})")
            try:
                writer.close()
            except Exception:
                pass

    @staticmethod
    def _tune_socket(writer: asyncio.StreamWriter) -> None:
        sock: socket.socket | None = writer.get_extra_info("socket")
        if sock is None:
            return
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass

    def client_count(self) -> int:
        return len(self._clients)

    async def close_clients(self) -> int:
        """Fault: force-close every connected control socket (drops the app's remote screen).

        The listener stays up, so the app's next reconnect attempt succeeds — this is the
        *transient* disruption. For the display-vanished shape, see :meth:`stop_listening`.
        """
        n = len(self._clients)
        for writer in list(self._clients):
            try:
                writer.close()
            except Exception:
                pass
        self._clients.clear()
        if n:
            self._log.fault(f"closed {n} RRC control socket(s)")
        else:
            self._log.fault("no RRC clients connected to close")
        return n

    async def stop_listening(self) -> None:
        """Fault: stop accepting control connections AND drop the current ones.

        With nothing bound to the port, every reconnect attempt is refused — the shape of a
        display that has left the network. This is what exercises the app's bounded-retry
        budget and its give-up-to-the-connect-screen path.

        **Clients are dropped first, and ``wait_closed`` is bounded.** From Python 3.12,
        ``Server.wait_closed()`` waits for every *connection handler* to finish, not just for the
        listening socket to close. The app's control socket is idle by design (the MFD never
        speaks, so the handler is parked in ``read()`` indefinitely), so awaiting it before
        dropping the clients hangs this coroutine forever — which is exactly what happened the
        first time this fault was fired at a live app.
        """
        await self.close_clients()
        if self._server is not None:
            self._server.close()
            try:
                await asyncio.wait_for(self._server.wait_closed(), timeout=3)
            except asyncio.TimeoutError:
                # The listening socket is shut either way; a straggling handler must not wedge
                # the fault. Carry on and report the port down.
                self._log.fault("note: a client handler outlived wait_closed; listener is down anyway")
            self._server = None
            self._log.fault(f"RRC listener DOWN — tcp:{self._port} now refuses connections")

    async def resume_listening(self) -> None:
        if self._server is None:
            await self.start()
            self._log.fault(f"RRC listener UP — tcp:{self._port} accepting again")

    def set_stalled(self, on: bool) -> None:
        """Fault: freeze/unfreeze reading on every control socket without closing anything.

        The connection stays ESTABLISHED while frames pile up unread — a hung display. Note the
        app cannot *see* this until its own send path backs up; that invisibility is precisely
        what the fault exists to demonstrate (and what the app's write watchdog bounds).
        """
        if on:
            self._stall_gate.clear()
            self._log.fault("RRC read STALLED — sockets stay open, frames go nowhere")
        else:
            self._stall_gate.set()
            self._log.fault("RRC read resumed")

    async def stop(self) -> None:
        # Same bounded wait as stop_listening, and for the same reason: an idle client handler
        # would otherwise hold shutdown open forever.
        await self.close_clients()
        if self._server is not None:
            self._server.close()
            try:
                await asyncio.wait_for(self._server.wait_closed(), timeout=3)
            except asyncio.TimeoutError:
                pass
            self._server = None
