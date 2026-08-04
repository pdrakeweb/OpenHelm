"""Live in-process smoke test: start the real Discovery + RRC server, resolve the services via
a second Zeroconf instance, and push real client bytes through the control socket.

Run:  .venv/Scripts/python.exe scripts/smoke_test.py

This validates the runtime wiring on one host. The mDNS *resolve* step depends on same-host
multicast loopback and is reported as INFO (not a hard failure) — content correctness is already
proven by the pytest suite (test_discovery.py replays the app's exact parser). On a real
Genymotion bridged setup the guest resolves these adverts normally.
"""

from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from zeroconf.asyncio import AsyncServiceInfo, AsyncZeroconf

from mfd_emulator import rrc_codec as rc
from mfd_emulator.config import load_config
from mfd_emulator.discovery import RRC_TYPE, RTSP_TYPE, Discovery
from mfd_emulator.events import EventLog
from mfd_emulator.rrc_server import RrcServer


async def main() -> int:
    cfg = load_config(None)
    ip = cfg.resolved_interface()
    log = EventLog()
    captured: list[str] = []
    log.subscribe(captured.append)

    ok = True
    discovery = Discovery(cfg, ip, log)
    rrc = RrcServer(ip, cfg.rrc.port, log)
    await rrc.start()
    await discovery.start(advertise_rtsp=True)

    # --- resolve the adverts from a second Zeroconf (best-effort) ---
    await asyncio.sleep(1.0)
    browser = AsyncZeroconf(interfaces=[ip])
    try:
        for type_, name in ((RTSP_TYPE, f"RaymarineMFD.{RTSP_TYPE}"), (RRC_TYPE, f"RaymarineMFD.{RRC_TYPE}")):
            info = AsyncServiceInfo(type_, name)
            found = await info.async_request(browser.zeroconf, 3000)
            if not found:
                print(f"[INFO] mDNS resolve of {type_} returned None (same-host loopback quirk; not fatal)")
            else:
                props = {k.decode() if isinstance(k, bytes) else k:
                         (b"" if v is None else v).decode(errors="replace") for k, v in info.properties.items()}
                print(f"[ OK ] resolved {type_}: port={info.port} txt_keys={sorted(props)}")
    finally:
        await browser.async_close()

    # --- push real client bytes through the control socket ---
    reader, writer = await asyncio.open_connection("127.0.0.1", cfg.rrc.port)
    writer.write(rc.encode_button(120, rc.ACTION_DOWN))   # MENU down
    writer.write(rc.encode_button(120, rc.ACTION_UP))     # MENU up
    writer.write(rc.encode_pointer(15, -3))               # cursor drag
    writer.write(rc.encode_button(13, rc.ACTION_DOWN))    # OK
    await writer.drain()
    await asyncio.sleep(0.3)
    writer.close()

    text = "\n".join(captured)
    for needle in ("button  MENU        down", "button  MENU        up",
                   "pointer cursor      dx=+15 dy=-3", "button  OK/ENTER    down"):
        status = "OK  " if needle in text else "FAIL"
        if needle not in text:
            ok = False
        print(f"[{status}] control decode: {needle!r}")

    await discovery.stop()
    await rrc.stop()
    log.close()
    print("\nSMOKE", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
