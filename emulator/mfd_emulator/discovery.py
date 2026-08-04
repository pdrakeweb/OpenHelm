"""mDNS / DNS-SD advertising via python-zeroconf.

Publishes the two services a remote browses for. The stock client's mDNS library renders each
TXT record as ``\t<key>: <value>\n`` and parses those strings by substring, so the TXT keys
below must match byte-for-byte. Key case is preserved rather than lowercased, which is why the
``RAYMARINEMFD`` presence marker survives a case-sensitive substring test.

- ``_rtsp._tcp.local.``    TXT: RAYMARINEMFD, raymarine-mfd-rtsp-path/-model/-serial.
                          App plays ``rtsp://<A-record-ip>:<SRV-port>/<rtsp-path>``.
- ``_rym_rrc._tcp.local.`` TXT: raymarine-mfd-rrc-version (value[2:4] parsed as hex -> frame byte[5]).

Both services publish an A record for a shared ``server`` hostname pointing at the host's LAN
IP, because jmDNS resolves the connect address from that A record. In Genymotion bridged mode
that LAN IP is directly reachable by the guest.

``_rym_pt._udp`` (pan/tilt slew) is intentionally NOT advertised in v1 (decision Q6).
"""

from __future__ import annotations

import socket

from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

from .config import Config
from .events import EventLog

RTSP_TYPE = "_rtsp._tcp.local."
RRC_TYPE = "_rym_rrc._tcp.local."
SERVER_HOST = "raymarine-mfd.local."


def build_rtsp_info(cfg: Config, ip: str) -> ServiceInfo:
    """The ``_rtsp._tcp`` advert. TXT keys must match ``as.java`` byte-for-byte.

    A live Raymarine E9 (docs/mfd-live-capture.md) advertises exactly three TXT keys here::

        raymarine-mfd-rtsp-path = RAYMARINEMFD
        raymarine-mfd-model     = E9
        raymarine-mfd-serial    = E70021 0620410

    i.e. the real MFD has **no** separate ``RAYMARINEMFD`` marker key — the app's case-sensitive
    ``contains("RAYMARINEMFD")`` gate (``as.java``) is satisfied by the *value* of the rtsp-path
    key. So when the configured path already carries the token we omit the synthetic marker and
    the advert is byte-identical in shape to the real device; when someone configures a different
    path (e.g. ``stream``) we still add the marker so the app doesn't reject the service.
    """
    props: dict[str, str] = {
        "raymarine-mfd-rtsp-path": cfg.rtsp.path,
        "raymarine-mfd-model": cfg.model,
        "raymarine-mfd-serial": cfg.serial,
    }
    if "RAYMARINEMFD" not in cfg.rtsp.path:
        props = {"RAYMARINEMFD": "1", **props}  # presence gate: as.java requires this token
    return ServiceInfo(
        RTSP_TYPE,
        f"RaymarineMFD.{RTSP_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=cfg.rtsp.port,
        server=SERVER_HOST,
        properties=props,
    )


def build_rrc_info(cfg: Config, ip: str) -> ServiceInfo:
    """The ``_rym_rrc._tcp`` advert. TXT ``raymarine-mfd-rrc-version`` per ``ag.java``."""
    return ServiceInfo(
        RRC_TYPE,
        f"RaymarineMFD.{RRC_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=cfg.rrc.port,
        server=SERVER_HOST,
        properties={"raymarine-mfd-rrc-version": cfg.rrc.version},
    )


class Discovery:
    """Async DNS-SD advertiser. Uses ``AsyncZeroconf`` because the whole emulator runs under one
    asyncio loop — the synchronous ``Zeroconf`` facade cannot be driven from inside a running
    loop (it raises ``EventLoopBlocked``)."""

    def __init__(self, cfg: Config, ip: str, log: EventLog) -> None:
        self._cfg = cfg
        self._ip = ip
        self._log = log
        self._aiozc: AsyncZeroconf | None = None
        self._rtsp_info: ServiceInfo | None = None
        self._rrc_info: ServiceInfo | None = None
        self._rtsp_registered = False
        self._rrc_registered = False

    # -- lifecycle -----------------------------------------------------------

    async def start(self, advertise_rtsp: bool = True) -> None:
        self._aiozc = AsyncZeroconf(interfaces=[self._ip])
        self._rtsp_info = build_rtsp_info(self._cfg, self._ip)
        self._rrc_info = build_rrc_info(self._cfg, self._ip)

        await self.register_rrc()
        if advertise_rtsp:
            await self.register_rtsp()
        self._log.mdns(
            f"advertising on {self._ip} - "
            f"rtsp://{self._ip}:{self._cfg.rtsp.port}/{self._cfg.rtsp.path}, "
            f"rrc tcp:{self._cfg.rrc.port} (version {self._cfg.rrc.version})"
        )

    async def stop(self) -> None:
        if self._aiozc is None:
            return
        try:
            await self._aiozc.async_unregister_all_services()
        finally:
            await self._aiozc.async_close()
            self._aiozc = None
            self._rtsp_registered = False
            self._rrc_registered = False
            self._log.mdns("stopped advertising")

    # -- per-service control (used by fault injection) -----------------------

    async def register_rtsp(self) -> None:
        if self._aiozc and self._rtsp_info and not self._rtsp_registered:
            # strict=False: the real MFD's sibling type _rym_rrc._tcp has an underscore in its
            # service label (non-RFC-6763), so we relax validation project-wide for consistency.
            await self._aiozc.async_register_service(self._rtsp_info, strict=False)
            self._rtsp_registered = True
            self._log.mdns(f"_rtsp._tcp registered (port {self._cfg.rtsp.port})")

    async def unregister_rtsp(self) -> None:
        if self._aiozc and self._rtsp_info and self._rtsp_registered:
            await self._aiozc.async_unregister_service(self._rtsp_info)
            self._rtsp_registered = False
            self._log.mdns("_rtsp._tcp UNREGISTERED (app will hit discovery timeout)")

    async def register_rrc(self) -> None:
        if self._aiozc and self._rrc_info and not self._rrc_registered:
            # strict=False: _rym_rrc has an underscore in the service label (non-RFC-6763) that
            # python-zeroconf would otherwise reject; the real MFD advertises exactly this name.
            await self._aiozc.async_register_service(self._rrc_info, strict=False)
            self._rrc_registered = True
            self._log.mdns(f"_rym_rrc._tcp registered (port {self._cfg.rrc.port})")

    async def unregister_rrc(self) -> None:
        if self._aiozc and self._rrc_info and self._rrc_registered:
            await self._aiozc.async_unregister_service(self._rrc_info)
            self._rrc_registered = False
            self._log.mdns("_rym_rrc._tcp UNREGISTERED")

    @property
    def rtsp_active(self) -> bool:
        return self._rtsp_registered
