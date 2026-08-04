#!/usr/bin/env python3
"""find_mfd.py — discover a Raymarine MFD on the local network and print its RTSP URL.

Run this on a laptop joined to the MFD's Wi-Fi network. It browses the same mDNS/DNS-SD
service types a remote app browses, prints every TXT record verbatim, and assembles the
``rtsp://<ip>:<port>/<raymarine-mfd-rtsp-path>`` URL the app would play — so you can paste that
URL into VLC and confirm, independently of the app, that the MFD is really streaming.

    python find_mfd.py                     # browse for 10 s on the default interface
    python find_mfd.py --timeout 20        # slower/busier network
    python find_mfd.py --interface 192.168.1.23   # pin the bind IP (multi-homed laptop)
    python find_mfd.py --json              # machine-readable

Service types browsed
---------------------
``_rtsp._tcp.local.``     the video stream. TXT carries ``RAYMARINEMFD`` (a presence token the
                          app gates on), ``raymarine-mfd-rtsp-path``, ``-model``, ``-serial``.
``_rym_rrc._tcp.local.``  the remote-control channel. TXT carries
                          ``raymarine-mfd-rrc-version`` (the app reads chars [2:4] as hex).
``_rym_pt._udp.local.``   the pan/tilt ("slew") channel, if the MFD has a controllable camera.

Note ``_rym_rrc`` has an underscore inside its *service label*, which RFC 6763 does not allow.
The emulator therefore registers with ``strict=False``. Browsing is unaffected: python-zeroconf
validates browse types with ``strict=False`` internally (``_services/browser.py``), so no extra
tolerance is needed on this side — but resolution is wrapped defensively all the same.

WHY THIS TOOL EXISTS — the codec question
-----------------------------------------
We do **not** actually know which codec a real MFD emits. The stock 2017 ``libstreamerr.so``
negotiated the codec from the RTSP/SDP and supported five formats (H.264, MPEG-4 ES,
MPEG4-GENERIC, MPEG-1/2, JPEG); our arm64 rebuild of that library appears to have hardcoded a
single path on an unverified assumption. The only third-party write-up of the
protocol (Tucker Osman, 2020) never names a codec — the author simply opened the URL in VLC.

So the single most valuable thing to capture at the boat is the **ffprobe output**, which names
the codec the MFD actually sends. This tool prints a ready-to-paste ffprobe command for exactly
that reason.

"Which machine should I run this on?"
-------------------------------------
The blog post that documented the protocol doesn't state the author's platform, but
they were doing packet analysis, so it was almost certainly a **laptop**. A laptop is the right
choice here regardless: you have to be joined to the MFD's Wi-Fi to play the stream at all, and
``ffprobe`` — the thing that identifies the codec — lives on the laptop, not the phone.

Exit codes
----------
0  at least one service found (and, if it was an ``_rtsp._tcp`` service, a URL was printed)
1  nothing found at all
2  something found, but no ``_rtsp._tcp`` service — no URL could be built
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
from dataclasses import dataclass, field
from typing import Iterable, Sequence

from zeroconf import ServiceBrowser, ServiceStateChange, Zeroconf

RTSP_TYPE = "_rtsp._tcp.local."
RRC_TYPE = "_rym_rrc._tcp.local."
PT_TYPE = "_rym_pt._udp.local."

SERVICE_TYPES: tuple[str, ...] = (RTSP_TYPE, RRC_TYPE, PT_TYPE)

# How long to wait for a single instance's SRV/TXT/A resolution, in milliseconds.
RESOLVE_TIMEOUT_MS = 3000


@dataclass
class FoundService:
    """One resolved DNS-SD service instance, with its TXT records kept verbatim."""

    service_type: str
    name: str  # full instance name, e.g. "RaymarineMFD._rtsp._tcp.local."
    server: str  # hostname from the SRV record, e.g. "raymarine-mfd.local."
    port: int
    addresses: list[str] = field(default_factory=list)
    txt: dict[str, str] = field(default_factory=dict)

    @property
    def instance(self) -> str:
        """The human-facing instance label (the part before the service type)."""
        if self.name.endswith("." + self.service_type):
            return self.name[: -(len(self.service_type) + 1)]
        return self.name

    @property
    def address(self) -> str | None:
        return self.addresses[0] if self.addresses else None

    @property
    def rtsp_url(self) -> str | None:
        """``rtsp://<ip>:<port>/<raymarine-mfd-rtsp-path>`` — exactly what the app builds.

        Mirrors ``as.java`` (``RtspServiceListener.serviceResolved``): the app takes the A-record
        address and SRV port, and appends the ``raymarine-mfd-rtsp-path`` TXT value.
        """
        if self.service_type != RTSP_TYPE:
            return None
        ip = self.address
        if not ip:
            return None
        path = self.txt.get("raymarine-mfd-rtsp-path", "").lstrip("/")
        return f"rtsp://{ip}:{self.port}/{path}"

    def to_dict(self) -> dict:
        return {
            "service_type": self.service_type,
            "name": self.name,
            "instance": self.instance,
            "server": self.server,
            "port": self.port,
            "addresses": self.addresses,
            "txt": self.txt,
            "rtsp_url": self.rtsp_url,
        }


def _decode_txt(info) -> dict[str, str]:
    """TXT records as ``str -> str``, preserving key case and order.

    python-zeroconf exposes ``decoded_properties`` on newer versions and raw ``bytes`` on older
    ones; handle both. A valueless TXT key decodes to an empty string (jmDNS on the app side
    renders it the same way).
    """
    decoded = getattr(info, "decoded_properties", None)
    if decoded is not None:
        return {str(k): ("" if v is None else str(v)) for k, v in decoded.items()}
    out: dict[str, str] = {}
    for k, v in (info.properties or {}).items():
        key = k.decode("utf-8", "replace") if isinstance(k, bytes) else str(k)
        if v is None:
            out[key] = ""
        elif isinstance(v, bytes):
            out[key] = v.decode("utf-8", "replace")
        else:
            out[key] = str(v)
    return out


def _addresses(info) -> list[str]:
    out: list[str] = []
    for packed in info.addresses or []:
        try:
            if len(packed) == 4:
                out.append(socket.inet_ntop(socket.AF_INET, packed))
            elif len(packed) == 16:
                out.append(socket.inet_ntop(socket.AF_INET6, packed))
        except (OSError, ValueError):
            continue
    return out


def find_services(
    timeout: float = 10.0,
    interface: str | None = None,
    types: Sequence[str] = SERVICE_TYPES,
    zc: Zeroconf | None = None,
) -> list[FoundService]:
    """Browse ``types`` for ``timeout`` seconds and return every instance we could resolve.

    Pass ``interface`` to pin the bind IP on a multi-homed machine (VPN adapters and Hyper-V
    switches are the usual reason a browse finds nothing). Pass ``zc`` to reuse an existing
    ``Zeroconf`` instance — the caller then owns closing it.
    """
    owns_zc = zc is None
    if zc is None:
        zc = Zeroconf(interfaces=[interface]) if interface else Zeroconf()

    seen: dict[tuple[str, str], None] = {}

    def _on_change(zeroconf: Zeroconf, service_type: str, name: str, state_change: ServiceStateChange) -> None:
        if state_change in (ServiceStateChange.Added, ServiceStateChange.Updated):
            seen[(service_type, name)] = None

    browser = None
    try:
        browser = ServiceBrowser(zc, list(types), handlers=[_on_change])
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            time.sleep(0.1)

        found: list[FoundService] = []
        for service_type, name in list(seen):
            try:
                info = zc.get_service_info(service_type, name, timeout=RESOLVE_TIMEOUT_MS)
            except Exception as exc:  # non-RFC labels, malformed records — report, don't crash
                print(f"  ! could not resolve {name}: {exc}", file=sys.stderr)
                continue
            if info is None:
                print(f"  ! {name} advertised but did not resolve within "
                      f"{RESOLVE_TIMEOUT_MS} ms", file=sys.stderr)
                continue
            found.append(
                FoundService(
                    service_type=service_type,
                    name=name,
                    server=info.server or "",
                    port=info.port or 0,
                    addresses=_addresses(info),
                    txt=_decode_txt(info),
                )
            )
        found.sort(key=lambda f: (f.service_type, f.name))
        return found
    finally:
        if browser is not None:
            browser.cancel()
        if owns_zc:
            zc.close()


# -- rendering ---------------------------------------------------------------


def _quote(url: str) -> str:
    return f'"{url}"'


def render(found: Iterable[FoundService]) -> str:
    """The human-readable report. Kept as a pure function so tests can assert on it."""
    lines: list[str] = []
    found = list(found)
    rtsp = [f for f in found if f.service_type == RTSP_TYPE]

    for svc in found:
        lines.append("")
        lines.append("=" * 72)
        lines.append(f"{svc.service_type}   {svc.instance}")
        lines.append("-" * 72)
        lines.append(f"  instance   : {svc.name}")
        lines.append(f"  host       : {svc.server}")
        lines.append(f"  address(es): {', '.join(svc.addresses) or '(none)'}")
        lines.append(f"  port       : {svc.port}")
        if svc.txt:
            lines.append("  TXT records (verbatim):")
            for key, value in svc.txt.items():
                lines.append(f"      {key} = {value}")
        else:
            lines.append("  TXT records: (none)")

        if svc.service_type == RTSP_TYPE:
            url = svc.rtsp_url
            if url:
                lines.append("")
                lines.append(f"  RTSP URL   : {url}")
        elif svc.service_type == RRC_TYPE:
            version = svc.txt.get("raymarine-mfd-rrc-version")
            if version:
                # ag.java: chars [2:4] of the TXT value, parsed as hex -> RRC frame byte[5].
                try:
                    parsed = int(version[2:4], 16)
                    lines.append(f"  RRC version: {version}  -> protocol byte 0x{parsed:02x}")
                except (ValueError, IndexError):
                    lines.append(f"  RRC version: {version}  (unparseable as hex)")

    if rtsp:
        lines.append("")
        lines.append("=" * 72)
        lines.append("NEXT STEPS — paste these on the laptop that is joined to the MFD's Wi-Fi")
        lines.append("=" * 72)
        for svc in rtsp:
            url = svc.rtsp_url
            if not url:
                continue
            lines.append("")
            lines.append(f"  # 1. Does it play at all?")
            lines.append(f"  vlc {_quote(url)}")
            lines.append(f"  # (or: ffplay -rtsp_transport tcp {_quote(url)})")
            lines.append("")
            lines.append(f"  # 2. WHAT CODEC IS IT? <- this is the answer we actually need")
            lines.append(f"  ffprobe -v error -rtsp_transport tcp -i {_quote(url)} -show_streams")
            lines.append("")
            lines.append("  # 3. Capture 10 s of it, so the codec can be re-checked off the boat:")
            lines.append(f"  ffmpeg -rtsp_transport tcp -i {_quote(url)} -t 10 -c copy mfd_capture.mkv")
        lines.append("")
        lines.append("  The ffprobe output is the important one. We have never confirmed which")
        lines.append("  codec a real MFD emits — the stock app negotiated it from the SDP and")
        lines.append("  supported five different formats. Record the codec_name / profile /")
        lines.append("  width / height / r_frame_rate lines it prints.")
        lines.append("")
        lines.append("  If ffprobe hangs with the default (UDP) transport, that itself is a")
        lines.append("  finding worth noting — keep -rtsp_transport tcp in that case.")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="find_mfd.py",
        description="Discover a Raymarine MFD via mDNS and print its RTSP URL + TXT records.",
        epilog="Exit codes: 0 = found; 1 = nothing found; 2 = found something, but no _rtsp._tcp service.",
    )
    parser.add_argument("--timeout", type=float, default=10.0,
                        help="seconds to browse before reporting (default: 10)")
    parser.add_argument("--interface", default=None,
                        help="bind IP to browse from, e.g. 192.168.1.23 (default: all interfaces)")
    parser.add_argument("--type", dest="types", action="append", default=None,
                        help="service type to browse (repeatable); default: the three Raymarine types")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of the report")
    args = parser.parse_args(argv)

    types = tuple(args.types) if args.types else SERVICE_TYPES

    if not args.json:
        where = args.interface or "all interfaces"
        print(f"Browsing {where} for {args.timeout:g}s ...")
        for service_type in types:
            print(f"  {service_type}")
        print("(Be joined to the MFD's Wi-Fi. Nothing found? Try --timeout 30, or pin")
        print(" --interface to your Wi-Fi adapter's IPv4 — VPN/Hyper-V adapters hijack mDNS.)")

    try:
        found = find_services(timeout=args.timeout, interface=args.interface, types=types)
    except OSError as exc:
        print(f"error: could not start mDNS browsing: {exc}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps([f.to_dict() for f in found], indent=2))
    else:
        if not found:
            print("\nNo Raymarine services found.")
        else:
            print(render(found))

    if not found:
        return 1
    if not any(f.service_type == RTSP_TYPE for f in found):
        if not args.json:
            print("\nFound Raymarine services, but no _rtsp._tcp advert — no stream URL to build.")
            print("On a real MFD that usually means video sharing is off in the MFD's settings.")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
