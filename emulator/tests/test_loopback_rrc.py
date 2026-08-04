"""End-to-end: a real TCP client sends the exact bytes the app emits; assert the server
decodes them to the right oracle lines. This exercises rrc_server + rrc_codec + events with no
device in the loop."""

import asyncio
import socket

from mfd_emulator import rrc_codec as rc
from mfd_emulator.events import EventLog
from mfd_emulator.rrc_server import RrcServer


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


async def test_server_decodes_client_frames():
    log = EventLog()
    captured: list[str] = []
    log.subscribe(captured.append)

    port = _free_port()
    server = RrcServer("127.0.0.1", port, log)
    await server.start()
    try:
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        writer.write(rc.encode_button(120, rc.ACTION_DOWN))  # MENU down
        writer.write(rc.encode_button(120, rc.ACTION_UP))    # MENU up
        writer.write(rc.encode_pointer(12, -4))              # cursor drag
        await writer.drain()
        await asyncio.sleep(0.25)

        text = "\n".join(captured)
        assert "button  MENU        down" in text
        assert "button  MENU        up" in text
        assert "pointer cursor      dx=+12 dy=-4" in text
        assert server.client_count() == 1

        # fault path: force-close the control socket
        n = await server.close_clients()
        assert n == 1
        writer.close()
    finally:
        await server.stop()
