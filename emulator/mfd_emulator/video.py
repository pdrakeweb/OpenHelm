"""RTSP/H.264 video supervisor.

Delegates the actual RTSP serving to **MediaMTX** (the RTSP server) fed by **FFmpeg** (the
H.264 source), per decision Q3. The emulator only supervises the two subprocesses and exposes
kill/restart hooks for fault injection.

FFmpeg publishes to MediaMTX at ``rtsp://127.0.0.1:<port>/<path>``; the app reads the same path
via the LAN IP advertised in the ``_rtsp._tcp`` TXT record. H.264 is constrained to Baseline /
yuv420p with frequent keyframes to stay friendly to the app's vintage LIVE555 + FFmpeg 3.4.9
decoder.

If the binaries are not installed the supervisor degrades gracefully: it logs how to install
them (``scripts/install_media_tools.ps1``) and keeps running so the discovery + control paths
are still fully testable. The stream simply won't play until the tools are present.
"""

from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

from .config import Config
from .events import EventLog

# A font that exists on any Windows box; drawtext needs a real file, not a family name.
_FONT_CANDIDATES = (
    Path("C:/Windows/Fonts/consolab.ttf"),
    Path("C:/Windows/Fonts/consola.ttf"),
    Path("C:/Windows/Fonts/arialbd.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"),
)


def _drawtext_font() -> str:
    """`fontfile=` fragment for drawtext.

    A filtergraph uses ``:`` as its own option separator, and escaping a Windows drive colon
    through argv reliably is more trouble than it is worth — FFmpeg rejects both ``C:/…`` and
    ``C\\:/…`` here. Instead the drive letter is dropped: ``/Windows/Fonts/x.ttf`` is *drive
    relative* on Windows and resolves against the current drive, so the path carries no colon at
    all. Returns an empty string if no font is found, letting drawtext use its built-in default
    rather than failing the whole graph.
    """
    for p in _FONT_CANDIDATES:
        if p.exists():
            path = str(p).replace("\\", "/")
            if len(path) > 1 and path[1] == ":":  # strip "C:" -> drive-relative
                path = path[2:]
            return f"fontfile={path}:"
    return ""


def _clock_overlay(fps: int) -> str:
    """drawtext filter burning a large frame counter into every frame.

    Deliberately colon-free (see `_drawtext_font`): the frame number needs no time formatting,
    and it is the more precise measure anyway — one frame is 1/fps s, so reading two clients'
    counters off a single photo gives their latency difference to within a frame.
    """
    font = _drawtext_font()
    box = "box=1:boxcolor=black@0.85:boxborderw=14:fontcolor=white"
    return (
        f"drawtext={font}text='%{{n}}':x=(w-tw)/2:y=90:fontsize=190:{box},"
        f"drawtext={font}text='FRAME @ {fps}fps':x=(w-tw)/2:y=330:fontsize=40:{box}"
    )


def _find_binary(name: str, override: str | None, extra_dirs: list[Path]) -> str | None:
    if override:
        return override if Path(override).exists() else None
    found = shutil.which(name)
    if found:
        return found
    for d in extra_dirs:
        for candidate in (d / name, d / f"{name}.exe"):
            if candidate.exists():
                return str(candidate)
    return None


class VideoSupervisor:
    def __init__(self, cfg: Config, ip: str, log: EventLog, work_dir: Path,
                 mediamtx_path: str | None = None, ffmpeg_path: str | None = None) -> None:
        self._cfg = cfg
        self._ip = ip
        self._log = log
        self._work = work_dir
        bin_dirs = [work_dir.parent / "bin", work_dir.parent]
        self._mediamtx = _find_binary("mediamtx", mediamtx_path, bin_dirs)
        self._ffmpeg = _find_binary("ffmpeg", ffmpeg_path, bin_dirs)
        self._mtx_proc: asyncio.subprocess.Process | None = None
        self._ff_proc: asyncio.subprocess.Process | None = None

    @property
    def available(self) -> bool:
        return bool(self._mediamtx and self._ffmpeg)

    # -- lifecycle -----------------------------------------------------------

    async def start(self) -> None:
        if not self.available:
            missing = [n for n, p in (("mediamtx", self._mediamtx), ("ffmpeg", self._ffmpeg)) if not p]
            self._log.rtsp(
                f"video DISABLED — missing {', '.join(missing)}. "
                f"Install with scripts/install_media_tools.ps1 (discovery + control still work)."
            )
            return
        await self._start_mediamtx()
        await asyncio.sleep(0.8)  # let MediaMTX bind before FFmpeg publishes
        await self._start_ffmpeg()

    async def _start_mediamtx(self) -> None:
        self._work.mkdir(parents=True, exist_ok=True)
        cfg_path = self._work / "mediamtx.yml"
        # logLevel info (not warn): we need to see which transport each client negotiates and
        # whether a session is actually established -- that is the evidence that distinguishes
        # "the client never got RTP" from "the server never sent any".
        lines = [
            "logLevel: info",
            f"rtspAddress: :{self._cfg.rtsp.port}",
            "rtmp: no",
            "webrtc: no",
            "srt: no",
        ]
        if self._cfg.rtsp.hls:
            # HLS exists purely to give the **AVD** a working video path. It is plain HTTP, i.e.
            # TCP, so it traverses the emulator's SLIRP NAT -- unlike RTP-over-UDP, which cannot
            # (see docs/mfd-emulator-limitations.md). A real MFD serves no HLS, so this is an
            # explicit, deliberate deviation from MFD fidelity: use it to exercise the player and
            # Surface plumbing, never to characterise latency or transport behaviour.
            lines += [
                "hls: yes",
                f"hlsAddress: :{self._cfg.rtsp.hls_port}",
                "hlsAlwaysRemux: yes",       # publish continuously, not only while a client reads
                # mpegts, NOT lowLatency/fmp4. android.media.MediaPlayer's HLS parser predates
                # LL-HLS and rejects an EXT-X-PART playlist outright with what=1 extra=-1007
                # (ERROR_MALFORMED) -- measured, not assumed. MPEG-TS is the compatible variant.
                "hlsVariant: mpegts",
                # Minimum MediaMTX permits. HLS latency is roughly segmentCount x duration, and
                # measured ~9s at 7x1s -- so trim to the floor. Even then this path runs several
                # seconds behind live BY CONSTRUCTION; it is for "do pixels appear", never latency.
                f"hlsSegmentCount: {self._cfg.rtsp.hls_segment_count}",
                f"hlsSegmentDuration: {self._cfg.rtsp.hls_segment_duration}",
            ]
        else:
            lines.append("hls: no")
        if self._cfg.rtsp.transports:
            # Restricting the server's offered transports is the only way to push a client that
            # has no transport control of its own (android.media.MediaPlayer proposes UDP first
            # and cannot be told not to) onto TCP interleaving: if the server refuses UDP, the
            # client must fall back. See docs/video-pipeline-replacement.md.
            joined = ", ".join(self._cfg.rtsp.transports)
            lines.append(f"rtspTransports: [{joined}]")
        lines += ["paths:", "  all_others:"]
        cfg_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

        # Keep MediaMTX's own log: its session lines are primary evidence when playback fails.
        mtx_log = (self._work / "mediamtx.log").open("w", encoding="utf-8")
        assert self._mediamtx
        self._mtx_proc = await asyncio.create_subprocess_exec(
            self._mediamtx, str(cfg_path),
            stdout=mtx_log, stderr=asyncio.subprocess.STDOUT,
        )
        transports = ",".join(self._cfg.rtsp.transports) if self._cfg.rtsp.transports else "all"
        self._log.rtsp(f"MediaMTX started (RTSP :{self._cfg.rtsp.port}, transports={transports})")
        # Say the mode out loud. Which mode a run used decides what its result is even evidence
        # of, so it must be in the log rather than inferred from the command line afterwards.
        if self._cfg.rtsp.hls:
            self._log.rtsp(
                f"VIDEO MODE 2 'hls' — also serving HLS on :{self._cfg.rtsp.hls_port} "
                f"(http://<ip>:{self._cfg.rtsp.hls_port}/{self._cfg.rtsp.path}/index.m3u8). "
                f"AVD-compatible, NOT MFD-faithful: no real MFD serves HLS, and HLS sits seconds "
                f"behind live — never judge latency from this mode."
            )
        else:
            self._log.rtsp(
                "VIDEO MODE 1 'rtsp' — RTP over UDP, as the real MFD serves it. "
                "This mode CANNOT play on a standard AVD (SLIRP drops inbound UDP and the failure "
                "crashes mediaserver): use a real phone. See docs/mfd-emulator-limitations.md."
            )

    def _ffmpeg_cmd(self) -> list[str]:
        """Build the FFmpeg publish command.

        The encode parameters mirror the **real MFD's** bitstream, measured from a live Raymarine
        E9 (docs/mfd-live-capture.md): H.264 **High Profile, Level 4.0, 800x480, yuv420p**, carried
        as ``RTP/AVP`` payload type 96 (``a=rtpmap:96 H264/90000``). Payload type 96 + the 90 kHz
        clock are what MediaMTX emits for H.264 automatically, so they match without being set here.

        Previously this published Baseline @ 1280x720, which is *not* what the app sees on the boat
        — a decoder that only copes with Baseline would have passed here and failed on the real MFD.

        ``-vf scale=...`` is applied to looped sources so any clip is normalised to the MFD's frame
        size (the bundled Lake Erie loop is already 800x480, so the filter is a no-op for it).
        """
        assert self._ffmpeg
        r = self._cfg.rtsp
        target = f"rtsp://127.0.0.1:{r.port}/{r.path}"
        common_out = [
            "-c:v", "libx264",
            "-profile:v", r.profile,       # real MFD: high (profile_idc=100)
            "-level:v", r.level,           # real MFD: 4.0 (level_idc=40)
            "-pix_fmt", r.pix_fmt,
            "-g", str(r.gop), "-tune", "zerolatency", "-b:v", r.bitrate,
            "-r", str(r.fps),
            # NOTE: -rtsp_transport tcp here is the PUBLISH leg (this process -> MediaMTX over
            # loopback) and is unrelated to what the app negotiates for playback; the transports
            # the server offers the app are set by cfg.rtsp.transports in _start_mediamtx().
            "-f", "rtsp", "-rtsp_transport", "tcp", target,
        ]
        scale = f"scale={r.width}:{r.height}"
        src = r.source
        if src == "clock":
            # Latency-measurement source: every frame carries its own frame number and stream
            # timecode, burned in large enough to read from a phone photo.
            #
            # Why a frame counter rather than a wall clock: two clients watching the SAME stream
            # display the same frame at different wall-clock moments, so photographing them
            # together and subtracting the two burned-in frame numbers gives the latency
            # *difference* between them directly -- no clock sync, no absolute reference needed.
            # At r.fps, delta_frames / fps == seconds of extra buffering.
            return [self._ffmpeg, "-hide_banner", "-loglevel", "warning",
                    "-re", "-f", "lavfi", "-i",
                    f"testsrc2=size={r.width}x{r.height}:rate={r.fps}",
                    "-vf", _clock_overlay(r.fps), *common_out]
        if src.startswith("loop:"):
            # A relative clip path is resolved against the emulator root, not the working
            # directory: the config that ships names `assets/...`, and the emulator is just as
            # likely to be started from the repo root as from its own folder.
            clip = src[len("loop:"):]
            if not Path(clip).is_absolute():
                clip = str((Path(__file__).resolve().parent.parent / clip))
            return [self._ffmpeg, "-hide_banner", "-loglevel", "warning",
                    "-re", "-stream_loop", "-1", "-i", clip,
                    "-vf", scale, *common_out]
        # default: synthesized test pattern (has a built-in timestamp/counter overlay)
        return [self._ffmpeg, "-hide_banner", "-loglevel", "warning",
                "-re", "-f", "lavfi", "-i",
                f"testsrc=size={r.width}x{r.height}:rate={r.fps}", *common_out]

    async def _start_ffmpeg(self) -> None:
        self._ff_proc = await asyncio.create_subprocess_exec(
            *self._ffmpeg_cmd(),
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        r = self._cfg.rtsp
        self._log.rtsp(
            f"FFmpeg publishing '{r.source}' -> "
            f"rtsp://{self._ip}:{r.port}/{r.path}"
        )
        # Echo the negotiated bitstream shape: this is the line to compare against the real MFD's
        # SDP/SPS (docs/mfd-live-capture.md) when checking format fidelity.
        self._log.rtsp(
            f"video format: H.264 {r.profile}@L{r.level} {r.width}x{r.height} "
            f"{r.fps}fps {r.pix_fmt} (real E9: high@L4.0 800x480 yuv420p, RTP/AVP pt96)"
        )

    # -- fault hooks ---------------------------------------------------------

    @property
    def running(self) -> bool:
        """True while MediaMTX (the RTSP endpoint itself) is up."""
        return self._mtx_proc is not None and self._mtx_proc.returncode is None

    async def stop_all(self) -> None:
        """Fault: take the whole RTSP endpoint down (MediaMTX + FFmpeg).

        Different failure shape from :meth:`drop_stream`: with FFmpeg killed the RTSP server
        still answers and the session simply starves (the app's stall timeout case); with
        MediaMTX gone the TCP port refuses, so the app's RTSP *connection* fails outright —
        the video half of a display leaving the network.
        """
        if self.running or (self._ff_proc and self._ff_proc.returncode is None):
            await self.stop()
            self._log.fault(f"RTSP endpoint DOWN — tcp:{self._cfg.rtsp.port} now refuses connections")

    async def start_all(self) -> None:
        if not self.available:
            return
        if self.running:
            await self.resume_stream()
            return
        await self.start()
        self._log.fault(f"RTSP endpoint UP — serving again on tcp:{self._cfg.rtsp.port}")

    async def drop_stream(self) -> None:
        """Fault: kill FFmpeg (mid-stream drop) but leave MediaMTX up."""
        if self._ff_proc and self._ff_proc.returncode is None:
            self._ff_proc.terminate()
            try:
                await asyncio.wait_for(self._ff_proc.wait(), timeout=3)
            except asyncio.TimeoutError:
                self._ff_proc.kill()
            self._log.fault("RTSP stream dropped (FFmpeg killed)")
        else:
            self._log.fault("no FFmpeg stream to drop")

    async def resume_stream(self) -> None:
        if not self.available:
            return
        if self._ff_proc is None or self._ff_proc.returncode is not None:
            await self._start_ffmpeg()
            self._log.fault("RTSP stream resumed")

    # -- shutdown ------------------------------------------------------------

    async def stop(self) -> None:
        for proc in (self._ff_proc, self._mtx_proc):
            if proc and proc.returncode is None:
                proc.terminate()
                try:
                    await asyncio.wait_for(proc.wait(), timeout=3)
                except asyncio.TimeoutError:
                    proc.kill()
        self._ff_proc = None
        self._mtx_proc = None
