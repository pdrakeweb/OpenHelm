"""Configuration loading for the MFD emulator.

A single YAML file (default ``mfd.yaml``) describes the simulated MFD's identity and the
three service ports. Anything omitted falls back to a sensible default; ``interface`` is
auto-detected (the host's primary LAN IPv4) when left unset.
"""

from __future__ import annotations

import socket
from dataclasses import dataclass, field
from pathlib import Path

import yaml


VIDEO_MODES = ("rtsp", "hls")


def _video_mode(value: object) -> str:
    """Validate rtsp.mode. A typo must fail loudly, not silently fall back to the other mode --
    the two modes differ in MFD fidelity, so quietly picking the wrong one invalidates a test."""
    mode = str(value).strip().lower()
    if mode not in VIDEO_MODES:
        raise ValueError(f"rtsp.mode must be one of {VIDEO_MODES}, got {value!r}")
    return mode


def detect_lan_ip() -> str:
    """Best-effort primary LAN IPv4 of this host.

    Opens a UDP socket "toward" a public address (no packets are actually sent) and reads
    back the local address the OS would route through. Falls back to 127.0.0.1.
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


@dataclass
class RtspConfig:
    # NOT 8554: the Android Emulator's own QEMU process binds 127.0.0.1:8554 for its internal
    # gRPC console service. On an AVD, a guest connection to 10.0.2.2:8554 hits that binding
    # instead of being NATed to our RTSP server, causing an immediate EOF. Confirmed via
    # `netstat -ano` (the PID on 127.0.0.1:8554 was qemu-system-x86_64.exe, not ours) --
    # see docs/mfd-manual-address.md. 8555 avoids the collision.
    # (The REAL MFD serves RTSP on 8554 -- see docs/mfd-live-capture.md. Set `port: 8554` in
    # mfd.yaml for full fidelity on a bridged rig where the QEMU collision does not apply.)
    port: int = 8555
    path: str = "stream"
    # "testsrc" -> FFmpeg smpte/test pattern with overlay; "loop:<file>" -> loop an H.264 clip.
    source: str = "testsrc"
    # RTSP transports the server offers, e.g. ["tcp"] to refuse UDP. None = MediaMTX's default
    # (udp, multicast, tcp). Forcing ["tcp"] is how we push a client with no transport control
    # of its own onto TCP interleaving -- android.media.MediaPlayer always proposes UDP first.
    #
    # FIDELITY NOTE: the real MFD (GStreamer RTSP server) serves RTP over **UDP**; a TCP-interleaved
    # request to it stalls indefinitely (proven with ffmpeg -rtsp_transport tcp, see
    # docs/mfd-live-capture.md). None (offer UDP) is therefore the real-MFD-faithful setting.
    transports: list[str] | None = None

    # -- video mode -------------------------------------------------------------------------
    # MODE 1 "rtsp"  (default, MFD-FAITHFUL)  RTSP + RTP over UDP, exactly as the real E9 serves.
    #                This is the mode that matches everything measured off the boat
    #                (docs/mfd-live-capture.md). **It does not work on a standard AVD** -- SLIRP
    #                NAT cannot deliver inbound UDP, and the failure crashes Android's own
    #                mediaserver. Use it with a real phone (or a bridged VM), never an AVD.
    #
    # MODE 2 "hls"   (AVD WORKAROUND, deliberate deviation)  Also publishes HLS over HTTP/TCP,
    #                which traverses SLIRP normally, so an AVD can finally render video. A real
    #                MFD serves no HLS. Segment-based delivery is inherently several seconds
    #                behind live, so this mode validates plumbing, never latency.
    #
    # See docs/mfd-emulator-limitations.md §1/§1b and docs/video-latency.md.
    mode: str = "rtsp"
    hls_port: int = 8888
    # HLS playlist geometry. Tunable because sweeping it is how you separate "latency the HLS
    # segmenting imposes" (scales with count x duration) from "latency the player adds regardless"
    # (a constant floor). MediaMTX's minimum count is 3.
    hls_segment_count: int = 3
    hls_segment_duration: str = "1s"

    @property
    def hls(self) -> bool:
        return self.mode == "hls"

    # -- H.264 encode parameters ------------------------------------------------------------
    # Defaults are the REAL MFD's, measured from a live Raymarine E9 (docs/mfd-live-capture.md):
    # its SDP advertises `m=video 0 RTP/AVP 96` + `a=rtpmap:96 H264/90000`, and its
    # sprop-parameter-sets SPS decodes to High Profile (profile_idc=100), Level 4.0, 800x480.
    # Matching these means the app's decoder sees the same bitstream shape as on the boat.
    width: int = 800            # real E9: 800x480 (also exactly the app's hardcoded bitmap size)
    height: int = 480
    fps: int = 15               # not advertised in the MFD's SDP; 15 keeps the loop light
    profile: str = "high"       # real E9: High Profile (profile_idc=100). Was "baseline".
    level: str = "4.0"          # real E9: Level 4.0 (level_idc=40)
    pix_fmt: str = "yuv420p"    # real E9: 4:2:0 (implied by the SPS chroma_format_idc)
    gop: int = 30               # keyframe interval; keeps join-time short for the app
    bitrate: str = "2M"


@dataclass
class RrcConfig:
    # 50000 is what a real Raymarine E9 advertises for _rym_rrc._tcp (docs/mfd-live-capture.md).
    # The app never hard-codes this -- it takes the port from the SRV record (or from the
    # manual-address config line, which is generated from this value) -- so matching the real
    # device costs nothing and keeps captures comparable.
    port: int = 50000
    # The app parses value[2:4] as hex -> frame byte[5].
    # A real E9 advertises "1.10": chars [2:4] = "10" -> version byte 0x10 (NOT 0x01).
    version: str = "1.10"


@dataclass
class Config:
    interface: str = ""  # empty -> auto-detect
    model: str = "e125"
    serial: str = "0170799"
    rtsp: RtspConfig = field(default_factory=RtspConfig)
    rrc: RrcConfig = field(default_factory=RrcConfig)

    def resolved_interface(self) -> str:
        return self.interface or detect_lan_ip()

    @property
    def rrc_version_byte(self) -> int:
        """The version byte the app will place at frame[5], derived exactly as ag.java does:
        skip 2 chars of the TXT value, read 2, parse as hex."""
        v = self.rrc.version
        try:
            return int(v[2:4], 16)
        except (ValueError, IndexError):
            return 0x01  # app-side fallback when the TXT value is malformed

    def device_config_line(self, host: str) -> str:
        """The single line the on-device config file / manual-address field carries.

        Format: ``host:rtspPort:rrcPort:rtspPath:rrcVersionHex2`` — the app parses this to skip
        mDNS and connect directly (e.g. ``10.0.2.2:8554:2560:stream:01`` for the AVD host alias).
        """
        return f"{host}:{self.rtsp.port}:{self.rrc.port}:{self.rtsp.path}:{self.rrc_version_byte:02x}"

    def device_config_file(self, host: str, video: str | None = None) -> str:
        """Full contents of the on-device ``mfd.cfg``.

        Line 1 is the address line (see :meth:`device_config_line`). ``video`` optionally adds a
        ``video=native`` / ``video=mediaplayer`` selector line, which the app reads in
        the ``video=`` key to pick the video pipeline:

        * ``mediaplayer`` — ``android.media.MediaPlayer`` rendering to the StreamView Surface
          (the app's default when the key is absent);
        * ``native`` — the original LIVE555/ffmpeg JNI pipeline.

        The app skips blank lines and ``#`` comments in both roles, and never mistakes a
        ``key=value`` line for the address.
        """
        lines = [
            "# MFD remote on-device config - generated by `python -m mfd_emulator --emit-config`",
            self.device_config_line(host),
        ]
        if video is not None:
            lines.append(f"video={video}")
        return "\n".join(lines) + "\n"


def load_config(path: str | Path | None) -> Config:
    """Load config from ``path``; return defaults if ``path`` is None or missing."""
    cfg = Config()
    if path is None:
        return cfg
    p = Path(path)
    if not p.exists():
        return cfg
    data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
    if "interface" in data:
        cfg.interface = str(data["interface"])
    if "model" in data:
        cfg.model = str(data["model"])
    if "serial" in data:
        cfg.serial = str(data["serial"])
    rtsp = data.get("rtsp") or {}
    transports = rtsp.get("transports")
    cfg.rtsp = RtspConfig(
        port=int(rtsp.get("port", RtspConfig.port)),
        path=str(rtsp.get("path", RtspConfig.path)),
        source=str(rtsp.get("source", RtspConfig.source)),
        transports=[str(t) for t in transports] if transports else None,
        mode=_video_mode(rtsp.get("mode", RtspConfig.mode)),
        hls_port=int(rtsp.get("hls_port", RtspConfig.hls_port)),
        hls_segment_count=int(rtsp.get("hls_segment_count", RtspConfig.hls_segment_count)),
        hls_segment_duration=str(rtsp.get("hls_segment_duration", RtspConfig.hls_segment_duration)),
        # H.264 encode parameters (defaults = the real MFD's measured values)
        width=int(rtsp.get("width", RtspConfig.width)),
        height=int(rtsp.get("height", RtspConfig.height)),
        fps=int(rtsp.get("fps", RtspConfig.fps)),
        profile=str(rtsp.get("profile", RtspConfig.profile)),
        level=str(rtsp.get("level", RtspConfig.level)),
        pix_fmt=str(rtsp.get("pix_fmt", RtspConfig.pix_fmt)),
        gop=int(rtsp.get("gop", RtspConfig.gop)),
        bitrate=str(rtsp.get("bitrate", RtspConfig.bitrate)),
    )
    rrc = data.get("rrc") or {}
    cfg.rrc = RrcConfig(
        port=int(rrc.get("port", RrcConfig.port)),
        version=str(rrc.get("version", RrcConfig.version)),
    )
    return cfg
