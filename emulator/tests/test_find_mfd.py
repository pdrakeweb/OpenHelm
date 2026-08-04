"""Tests for ``scripts/find_mfd.py`` — the on-the-boat MFD discovery tool.

Two layers:

* pure-function tests over ``FoundService`` / ``render()`` — no network, instant;
* one live loopback test that advertises with the emulator's own ``Discovery`` and asserts the
  finder discovers it, resolves the TXT records verbatim, and builds the right RTSP URL.

The live test runs the *synchronous* zeroconf browser in a worker thread while the emulator's
``AsyncZeroconf`` advertises from the event loop — two zeroconf instances in one process, both
bound to 127.0.0.1. That is exactly the shape of the real thing (separate laptop browsing a
separate MFD), just collapsed onto loopback.
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

from find_mfd import (  # noqa: E402
    PT_TYPE,
    RRC_TYPE,
    RTSP_TYPE,
    FoundService,
    find_services,
    render,
)

from mfd_emulator.config import Config  # noqa: E402
from mfd_emulator.discovery import Discovery  # noqa: E402
from mfd_emulator.events import EventLog  # noqa: E402

LOOPBACK = "127.0.0.1"

# The emulator staggers its two registrations ~1 s apart, so the browse window has to outlast
# that plus a probe cycle. 6 s is comfortable on loopback without making the suite crawl.
BROWSE_SECONDS = 6.0


# -- pure-function tests -----------------------------------------------------


def _rtsp_service(path: str = "stream", ip: str = "192.168.1.50", port: int = 8555) -> FoundService:
    return FoundService(
        service_type=RTSP_TYPE,
        name=f"RaymarineMFD.{RTSP_TYPE}",
        server="raymarine-mfd.local.",
        port=port,
        addresses=[ip],
        txt={
            "RAYMARINEMFD": "1",
            "raymarine-mfd-rtsp-path": path,
            "raymarine-mfd-model": "e125",
            "raymarine-mfd-serial": "0170799",
        },
    )


def test_instance_label_strips_service_type():
    assert _rtsp_service().instance == "RaymarineMFD"


def test_rtsp_url_matches_what_the_app_builds():
    # as.java builds rtsp://<A-record ip>:<SRV port>/<raymarine-mfd-rtsp-path>.
    assert _rtsp_service().rtsp_url == "rtsp://192.168.1.50:8555/stream"


def test_rtsp_url_tolerates_a_leading_slash_in_the_path():
    assert _rtsp_service(path="/stream/0").rtsp_url == "rtsp://192.168.1.50:8555/stream/0"


def test_non_rtsp_service_has_no_url():
    svc = FoundService(service_type=RRC_TYPE, name=f"RaymarineMFD.{RRC_TYPE}",
                       server="raymarine-mfd.local.", port=2560, addresses=["192.168.1.50"],
                       txt={"raymarine-mfd-rrc-version": "0x01"})
    assert svc.rtsp_url is None


def test_render_prints_txt_verbatim_and_the_ffprobe_hint():
    out = render([_rtsp_service()])
    assert "raymarine-mfd-rtsp-path = stream" in out
    assert "RAYMARINEMFD = 1" in out
    assert "rtsp://192.168.1.50:8555/stream" in out
    assert "ffprobe" in out
    assert "vlc" in out
    # The tool must say out loud that ffprobe is what reveals the codec.
    assert "CODEC" in out.upper()


def test_render_decodes_the_rrc_version_the_way_the_app_does():
    svc = FoundService(service_type=RRC_TYPE, name=f"RaymarineMFD.{RRC_TYPE}",
                       server="raymarine-mfd.local.", port=2560, addresses=["10.0.0.9"],
                       txt={"raymarine-mfd-rrc-version": "0x0F"})
    # ag.java takes chars [2:4] as hex -> RRC frame byte[5].
    assert "protocol byte 0x0f" in render([svc])


# -- live loopback test ------------------------------------------------------


@pytest.mark.asyncio
async def test_finder_discovers_the_emulator_over_loopback():
    cfg = Config()
    cfg.interface = LOOPBACK
    log = EventLog()
    discovery = Discovery(cfg, LOOPBACK, log)
    await discovery.start(advertise_rtsp=True)
    try:
        found = await asyncio.to_thread(
            find_services,
            timeout=BROWSE_SECONDS,
            interface=LOOPBACK,
            types=(RTSP_TYPE, RRC_TYPE, PT_TYPE),
        )
    finally:
        await discovery.stop()
        log.close()

    by_type = {f.service_type: f for f in found}
    assert RTSP_TYPE in by_type, f"finder did not see the _rtsp._tcp advert (saw: {list(by_type)})"
    assert RRC_TYPE in by_type, f"finder did not see the _rym_rrc._tcp advert (saw: {list(by_type)})"
    # _rym_pt._udp is deliberately not advertised by the emulator (decision Q6).
    assert PT_TYPE not in by_type

    rtsp = by_type[RTSP_TYPE]
    assert rtsp.instance == "RaymarineMFD"
    assert rtsp.port == cfg.rtsp.port == 8555
    assert rtsp.addresses == [LOOPBACK]
    assert rtsp.txt["RAYMARINEMFD"] == "1"
    assert rtsp.txt["raymarine-mfd-rtsp-path"] == cfg.rtsp.path
    assert rtsp.txt["raymarine-mfd-model"] == cfg.model
    assert rtsp.txt["raymarine-mfd-serial"] == cfg.serial
    assert rtsp.rtsp_url == f"rtsp://{LOOPBACK}:{cfg.rtsp.port}/{cfg.rtsp.path}"

    rrc = by_type[RRC_TYPE]
    assert rrc.port == cfg.rrc.port == 50000
    assert rrc.txt["raymarine-mfd-rrc-version"] == cfg.rrc.version

    report = render(found)
    assert f"rtsp://{LOOPBACK}:{cfg.rtsp.port}/{cfg.rtsp.path}" in report
    assert "ffprobe -v error -rtsp_transport tcp" in report
