"""MFD emulator entrypoint.

Wires discovery + RRC control server + RTSP video supervisor + fault console under one asyncio
loop. Run with ``python -m mfd_emulator`` (see ``run.ps1`` / ``run.sh``).
"""

from __future__ import annotations

import argparse
import asyncio
import signal
from pathlib import Path

from .config import load_config
from .console import Console
from .control import ControlServer
from .discovery import Discovery
from .events import EventLog
from .faults import FaultController
from .rrc_server import RrcServer
from .video import VideoSupervisor


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="mfd_emulator", description="Simulated Raymarine MFD for testing a remote app.")
    p.add_argument("--config", default=str(Path(__file__).resolve().parent.parent / "mfd.yaml"),
                   help="path to mfd.yaml (default: emulator/mfd.yaml)")
    p.add_argument("--interface", default=None, help="override the advertised LAN IPv4")
    p.add_argument("--log-file", default=None, help="also append the event log to this file")
    p.add_argument("--mediamtx", default=None, help="path to the mediamtx binary")
    p.add_argument("--ffmpeg", default=None, help="path to the ffmpeg binary")
    p.add_argument("--no-video", action="store_true", help="skip the RTSP video supervisor")
    p.add_argument("--video-mode", choices=("rtsp", "hls"), default=None,
                   help="MODE 1 'rtsp' (default): RTP over UDP exactly as the real MFD serves it — "
                        "MFD-faithful, but DOES NOT WORK on a standard AVD. MODE 2 'hls': also "
                        "serve HLS over HTTP/TCP so an AVD can render video — a real MFD does not "
                        "do this, and HLS latency is not representative.")
    p.add_argument("--source", default=None,
                   help="override rtsp.source: 'testsrc', 'clock' (burned-in frame counter for "
                        "latency measurement) or 'loop:C:/path/clip.mp4'")
    p.add_argument("--no-console", action="store_true", help="headless: no keyboard fault menu")
    p.add_argument("--control-port", type=int, default=8571, metavar="PORT",
                   help="TCP fault-control server on 127.0.0.1 (scripts/fault.py talks to it); "
                        "0 disables. Default 8571.")
    p.add_argument("--no-discovery", action="store_true",
                   help="don't advertise mDNS (use the on-device config/manual-address path instead)")
    p.add_argument("--emit-config", metavar="HOST", nargs="?", const="10.0.2.2",
                   help="print the on-device config for HOST (default 10.0.2.2 = AVD host alias) and exit")
    p.add_argument("--video", choices=("native", "mediaplayer"), default=None,
                   help="with --emit-config: also emit a 'video=' line selecting the app's video "
                        "pipeline (native = LIVE555/ffmpeg JNI, mediaplayer = android.media.MediaPlayer). "
                        "Omit to leave the app on its own default (mediaplayer).")
    p.add_argument("--rtsp-transports", default=None,
                   help="comma-separated RTSP transports the server offers, e.g. 'tcp' to refuse "
                        "UDP. Forcing tcp is the only way to move a client with no transport "
                        "control of its own (android.media.MediaPlayer) onto TCP interleaving.")
    return p.parse_args(argv)


async def run(args: argparse.Namespace) -> None:
    cfg = load_config(args.config)
    if args.interface:
        cfg.interface = args.interface
    if args.rtsp_transports:
        cfg.rtsp.transports = [t.strip() for t in args.rtsp_transports.split(",") if t.strip()]
    if args.source:
        cfg.rtsp.source = args.source
    if args.video_mode:
        cfg.rtsp.mode = args.video_mode

    if args.emit_config is not None:
        # On-device config: the app reads this to skip mDNS and connect directly, and (with
        # --video) to choose between the native and MediaPlayer video pipelines.
        if args.video is None:
            print(cfg.device_config_line(args.emit_config))
        else:
            print(cfg.device_config_file(args.emit_config, args.video), end="")
        return

    ip = cfg.resolved_interface()

    log = EventLog(file_path=args.log_file)
    log.sys(f"MFD emulator starting - model '{cfg.model}' serial '{cfg.serial}' on {ip}")

    work_dir = Path(__file__).resolve().parent.parent / ".run"
    discovery = Discovery(cfg, ip, log)
    rrc = RrcServer(ip, cfg.rrc.port, log)
    video = VideoSupervisor(cfg, ip, log, work_dir, mediamtx_path=args.mediamtx, ffmpeg_path=args.ffmpeg)
    faults = FaultController(discovery, rrc, video, log)

    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()

    # Ctrl+C / SIGTERM -> clean shutdown (add_signal_handler is unavailable on Windows).
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop_event.set)
    except NotImplementedError:
        pass

    control = ControlServer(faults, log, args.control_port) if args.control_port else None

    await rrc.start()
    if not args.no_video:
        await video.start()
    if control is not None:
        await control.start()
    if args.no_discovery:
        log.sys(f"mDNS advertising OFF - point the app here via config/manual address "
                f"(e.g. `{cfg.device_config_line('10.0.2.2')}` for the AVD)")
    else:
        await discovery.start(advertise_rtsp=True)

    if not args.no_console:
        Console(faults, loop, stop_event).start()
    log.sys("ready - open the remote app; it should reach this MFD (via mDNS, or config/manual address).")

    try:
        await stop_event.wait()
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        log.sys("shutting down...")
        if control is not None:
            await control.stop()
        await discovery.stop()
        await rrc.stop()
        await video.stop()
        log.close()


def main() -> None:
    args = parse_args()
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
