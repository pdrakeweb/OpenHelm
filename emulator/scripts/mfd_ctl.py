"""mfd_ctl — talk to a REAL Raymarine MFD: discover it, send control frames, grab screenshots.

This is the *client* side (the emulator in this repo is the server side). It exists so we can run
experiments against a real MFD without the Android app in the loop — in particular to settle the
open question of what the RRC **pointer** opcode's two int16s actually mean (absolute pixels?
relative deltas? what origin?), which no amount of reading the protocol description settles.

Everything it sends is the wire format the emulator's own codec implements.

Usage (all commands auto-discover the MFD over mDNS unless you pass --host):

    python scripts/mfd_ctl.py discover
    python scripts/mfd_ctl.py shot --out before.png
    python scripts/mfd_ctl.py button MENU
    python scripts/mfd_ctl.py pointer 100 100
    python scripts/mfd_ctl.py probe 100,100 700,400        # the pointer experiment
    python scripts/mfd_ctl.py sweep --axis x --steps 5     # sweep one axis, shot after each

Notes / gotchas baked in:
  * The RRC version byte is NOT a version number. The app reads chars [2:4] of the
    `raymarine-mfd-rrc-version` TXT value as HEX, so a real E9's "1.10" -> 0x10. We copy that.
  * The real MFD serves RTP over **UDP**; `-rtsp_transport tcp` stalls it forever. Screenshots
    therefore default to UDP.
  * The MFD never replies on the RRC socket (it is write-only in practice), so nothing here waits
    for a response. A read returning EOF means the MFD dropped us.
"""

from __future__ import annotations

import argparse
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from mfd_emulator import rrc_codec  # noqa: E402

RTSP_TYPE = "_rtsp._tcp.local."
RRC_TYPE = "_rym_rrc._tcp.local."


# ----------------------------------------------------------------------------- discovery

@dataclass
class Mfd:
    host: str
    rtsp_port: int = 8554
    rtsp_path: str = "RAYMARINEMFD"
    rrc_port: int = 50000
    version_byte: int = 0x10
    model: str = "?"
    serial: str = "?"

    @property
    def rtsp_url(self) -> str:
        return f"rtsp://{self.host}:{self.rtsp_port}/{self.rtsp_path}"

    def describe(self) -> str:
        return (f"{self.model} {self.serial} @ {self.host}\n"
                f"  RTSP : {self.rtsp_url}\n"
                f"  RRC  : tcp/{self.rrc_port}  version byte 0x{self.version_byte:02x}")


def _txt(info, key: str, default: str = "") -> str:
    """Read a TXT value regardless of python-zeroconf version."""
    dp = getattr(info, "decoded_properties", None)
    if dp is not None:
        v = dp.get(key)
        return default if v is None else str(v)
    v = info.properties.get(key.encode())
    return default if v is None else v.decode(errors="replace")


def discover(timeout: float = 12.0, iface: str | None = None) -> Mfd | None:
    """Browse mDNS for the MFD's two services and merge them into one Mfd."""
    from zeroconf import ServiceBrowser, Zeroconf

    found: dict[str, object] = {}
    # NB: python-zeroconf rejects interfaces=None -- the kwarg must be omitted entirely to get
    # its default (all interfaces).
    zc = Zeroconf(interfaces=[iface]) if iface else Zeroconf()

    class L:
        def add_service(self, zc_, type_, name):
            info = zc_.get_service_info(type_, name, timeout=3000)
            if info:
                found[type_] = info

        def update_service(self, *a):
            pass

        def remove_service(self, *a):
            pass

    listener = L()
    ServiceBrowser(zc, [RTSP_TYPE, RRC_TYPE], listener)
    deadline = time.time() + timeout
    while time.time() < deadline and len(found) < 2:
        time.sleep(0.25)
    zc.close()

    if RTSP_TYPE not in found and RRC_TYPE not in found:
        return None

    mfd = Mfd(host="")
    if RTSP_TYPE in found:
        i = found[RTSP_TYPE]
        mfd.host = socket.inet_ntoa(i.addresses[0])
        mfd.rtsp_port = i.port
        mfd.rtsp_path = _txt(i, "raymarine-mfd-rtsp-path", mfd.rtsp_path)
        mfd.model = _txt(i, "raymarine-mfd-model", mfd.model)
        mfd.serial = _txt(i, "raymarine-mfd-serial", mfd.serial)
    if RRC_TYPE in found:
        i = found[RRC_TYPE]
        mfd.host = mfd.host or socket.inet_ntoa(i.addresses[0])
        mfd.rrc_port = i.port
        raw = _txt(i, "raymarine-mfd-rrc-version", "")
        # The app's own parse: chars [2:4] read as hex. "1.10" -> "10" -> 0x10.
        try:
            mfd.version_byte = int(raw[2:4], 16)
        except (ValueError, IndexError):
            pass
        if mfd.model == "?":
            mfd.model = _txt(i, "raymarine-mfd-model", mfd.model)
            mfd.serial = _txt(i, "raymarine-mfd-serial", mfd.serial)
    return mfd if mfd.host else None


# ----------------------------------------------------------------------------- control

class Rrc:
    """One RRC control connection. Context manager; never expects a reply."""

    def __init__(self, mfd: Mfd, quiet: bool = False) -> None:
        self.mfd = mfd
        self.quiet = quiet
        self.sock: socket.socket | None = None

    def __enter__(self) -> "Rrc":
        s = socket.create_connection((self.mfd.host, self.mfd.rrc_port), timeout=8)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock = s
        if not self.quiet:
            print(f"[rrc] connected {self.mfd.host}:{self.mfd.rrc_port}")
        return self

    def __exit__(self, *exc) -> None:
        if self.sock:
            try:
                self.sock.close()
            finally:
                self.sock = None

    def _send(self, frame: bytes, label: str) -> None:
        assert self.sock
        self.sock.sendall(frame)
        if not self.quiet:
            print(f"[rrc] {label:<28} {frame.hex(' ')}")

    def button(self, name: str, hold: float = 0.05, repeat: int = 1,
               gap: float = 0.04) -> None:
        """Press a button `repeat` times. The app itself repeats direction keys while the
        on-screen joystick is held, so this is how cursor motion is actually produced."""
        code = BUTTONS[name.upper()]
        v = self.mfd.version_byte
        for i in range(repeat):
            self._send(rrc_codec.encode_button(code, rrc_codec.ACTION_DOWN, v),
                       f"{name.upper()} down [{i + 1}/{repeat}]")
            time.sleep(hold)
            self._send(rrc_codec.encode_button(code, rrc_codec.ACTION_UP, v),
                       f"{name.upper()} up   [{i + 1}/{repeat}]")
            if i + 1 < repeat:
                time.sleep(gap)

    def hold(self, name: str, seconds: float) -> None:
        """Send DOWN, wait, then UP -- i.e. a genuine key *hold* rather than repeated taps.
        Distinguishes 'MFD auto-repeats while held' from 'one step per press'."""
        code = BUTTONS[name.upper()]
        v = self.mfd.version_byte
        self._send(rrc_codec.encode_button(code, rrc_codec.ACTION_DOWN, v), f"{name.upper()} DOWN(hold)")
        time.sleep(seconds)
        self._send(rrc_codec.encode_button(code, rrc_codec.ACTION_UP, v), f"{name.upper()} UP")

    def pointer(self, x: int, y: int) -> None:
        self._send(rrc_codec.encode_pointer(x, y, self.mfd.version_byte), f"pointer ({x},{y})")

    def tap(self, x_px: int, y_px: int, w: int = 800, h: int = 480, hold: float = 0.12) -> None:
        """Tap at a PIXEL position on the MFD screen (default 800x480), via opcode 3.

        Coordinates go on the wire normalised to 0..65535, which is what the tablet remote does.
        A tap is DOWN then UP at the same point, with the move-counter reset (0) on both.
        """
        nx, ny = rrc_codec.norm_xy(x_px, y_px, w, h)
        v = self.mfd.version_byte
        self._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v),
                   f"touch DOWN px({x_px},{y_px})")
        time.sleep(hold)
        self._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 0, nx, ny, v),
                   f"touch UP   px({x_px},{y_px})")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, steps: int = 8,
              w: int = 800, h: int = 480, gap: float = 0.05) -> None:
        """Drag from one pixel position to another: DOWN, N MOVEs (seq increments), UP."""
        v = self.mfd.version_byte
        nx, ny = rrc_codec.norm_xy(x1, y1, w, h)
        self._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v),
                   f"touch DOWN px({x1},{y1})")
        for i in range(1, steps + 1):
            px = x1 + (x2 - x1) * i // steps
            py = y1 + (y2 - y1) * i // steps
            nx, ny = rrc_codec.norm_xy(px, py, w, h)
            time.sleep(gap)
            self._send(rrc_codec.encode_touch(rrc_codec.TOUCH_MOVE, i & 0xFF, nx, ny, v),
                       f"touch MOVE[{i}] px({px},{py})")
        time.sleep(gap)
        self._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 0, nx, ny, v),
                   f"touch UP   px({x2},{y2})")


BUTTONS = {name: code for code, name in rrc_codec.KEYCODES.items()}
BUTTONS["OK"] = 13
BUTTONS["ENTER"] = 13


# ----------------------------------------------------------------------------- screenshots

def _ffmpeg() -> str | None:
    found = shutil.which("ffmpeg")
    if found:
        return found
    guess = (Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft/WinGet/Packages/"
             "Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-8.1.2-full_build/bin/ffmpeg.exe")
    return str(guess) if guess.exists() else None


def screenshot(mfd: Mfd, out: Path, transport: str = "udp", timeout: int = 25) -> bool:
    """Grab a single frame from the MFD's live RTSP stream.

    UDP by default: the real MFD's GStreamer server stalls indefinitely on TCP interleaving.
    """
    ff = _ffmpeg()
    if not ff:
        print("!! ffmpeg not found - run scripts/install_media_tools.ps1", file=sys.stderr)
        return False
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = [ff, "-hide_banner", "-loglevel", "error",
           "-rtsp_transport", transport, "-i", mfd.rtsp_url,
           "-frames:v", "1", "-y", str(out)]
    try:
        subprocess.run(cmd, check=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"!! screenshot timed out after {timeout}s ({transport})", file=sys.stderr)
        return False
    except subprocess.CalledProcessError as e:
        print(f"!! ffmpeg failed ({e.returncode})", file=sys.stderr)
        return False
    ok = out.exists() and out.stat().st_size > 0
    print(f"[shot] {out}  ({out.stat().st_size} bytes)" if ok else "!! no frame captured")
    return ok


# ----------------------------------------------------------------------------- commands

def resolve(args) -> Mfd | None:
    if args.host:
        return Mfd(host=args.host, rtsp_port=args.rtsp_port, rtsp_path=args.rtsp_path,
                   rrc_port=args.rrc_port, version_byte=int(args.version, 16))
    print(f"discovering (up to {args.timeout}s) ...")
    mfd = discover(args.timeout, args.interface)
    if mfd:
        print(mfd.describe())
    else:
        print("!! no MFD found. Are you on the MFD's WiFi? Try --interface <your-ipv4>.",
              file=sys.stderr)
    return mfd


def cmd_probe(args, mfd: Mfd) -> int:
    """The pointer experiment: shot, send A, shot, send B, shot.

    Compare the three images to see whether a cursor appeared and where it went. That tells us
    whether the two int16s are absolute screen coordinates (and in what space) or relative deltas.
    """
    outdir = Path(args.outdir)
    points = []
    for token in args.points:
        x, y = token.split(",")
        points.append((int(x), int(y)))

    stamp = time.strftime("%H%M%S")
    screenshot(mfd, outdir / f"probe_{stamp}_0_before.png", args.transport)
    with Rrc(mfd) as rrc:
        for n, (x, y) in enumerate(points, start=1):
            rrc.pointer(x, y)
            time.sleep(args.settle)
            screenshot(mfd, outdir / f"probe_{stamp}_{n}_after_{x}_{y}.png", args.transport)
    print(f"\nCompare the images in {outdir}\\ — look for a cursor and where it sits.")
    return 0


def cmd_sweep(args, mfd: Mfd) -> int:
    """Sweep one axis across the frame, screenshotting each step — maps the coordinate space."""
    outdir = Path(args.outdir)
    stamp = time.strftime("%H%M%S")
    lo, hi = args.lo, args.hi
    step = max(1, (hi - lo) // max(1, args.steps - 1))
    with Rrc(mfd) as rrc:
        for i, v in enumerate(range(lo, hi + 1, step)):
            x, y = (v, args.fixed) if args.axis == "x" else (args.fixed, v)
            rrc.pointer(x, y)
            time.sleep(args.settle)
            screenshot(mfd, outdir / f"sweep_{stamp}_{args.axis}{v}.png", args.transport)
    return 0


def cmd_touch_probe(args, mfd: Mfd) -> int:
    """Settle how a REAL display wants opcode-3 touch, by trying the variants one at a time.

    Opcode 3 comes from the tablet remote and has never been confirmed against hardware
    (protocol-spec.md §4.4, §6.2). The first field report of it was a display that latched a
    button down and auto-repeated forever, and a drag that only registered where it started —
    i.e. the DOWN was honoured and the MOVE/UP were not.

    Watch the display while this runs. Each variant announces itself, does one thing, then waits
    for you. Report which ones the display actually obeyed; that decides the encoding.
    """
    x, y = args.x, args.y
    v = mfd.version_byte

    def pause(msg: str) -> None:
        print(f"\n    -> {msg}")
        input("       press Enter for the next variant ...")

    with Rrc(mfd) as rrc:
        print(f"\n=== touch probe at pixel ({x},{y}) on an {args.width}x{args.height} screen ===")
        nx, ny = rrc_codec.norm_xy(x, y, args.width, args.height)

        print("\n[1] DOWN then UP, 120 ms apart  (a normal tap; what the app now sends)")
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v), "DOWN")
        time.sleep(0.12)
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 0, nx, ny, v), "UP seq=0")
        pause("did that register as ONE tap, and then STOP?")

        print("\n[2] DOWN then UP, 1 ms apart  (what the app sent before — expected to latch)")
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v), "DOWN")
        time.sleep(0.001)
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 0, nx, ny, v), "UP seq=0")
        pause("did that latch / auto-repeat? (if yes, dwell was the bug)")

        print("\n[3] DOWN then UP carrying a CONTINUED seq, not 0")
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v), "DOWN seq=0")
        time.sleep(0.12)
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 1, nx, ny, v), "UP seq=1")
        pause("any different from [1]?")

        print("\n[4] drag: DOWN, 8 MOVEs with incrementing seq, UP")
        x2, y2 = args.x2, args.y2
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v), "DOWN")
        for i in range(1, 9):
            px = x + (x2 - x) * i // 8
            py = y + (y2 - y) * i // 8
            mx, my = rrc_codec.norm_xy(px, py, args.width, args.height)
            time.sleep(0.05)
            rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_MOVE, i, mx, my, v), f"MOVE[{i}]")
        ex, ey = rrc_codec.norm_xy(x2, y2, args.width, args.height)
        time.sleep(0.05)
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 0, ex, ey, v), "UP")
        pause("did anything TRACK the drag, or did it stay at the start?")

        print("\n[5] drag where every frame keeps incrementing seq, UP included")
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v), "DOWN seq=0")
        for i in range(1, 9):
            px = x + (x2 - x) * i // 8
            py = y + (y2 - y) * i // 8
            mx, my = rrc_codec.norm_xy(px, py, args.width, args.height)
            time.sleep(0.05)
            rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_MOVE, i, mx, my, v), f"MOVE[{i}]")
        time.sleep(0.05)
        rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 9, ex, ey, v), "UP seq=9")
        print("\n    -> did [5] track where [4] did not?")

    print("\nWhichever variants the display obeyed is the answer. Nothing here changes the app;\n"
          "it only establishes what the hardware wants.")
    return 0


def cmd_touch_encoding(args, mfd: Mfd) -> int:
    """Settle how the display *scales* a touch coordinate, by tapping one target four ways.

    ``touch-probe`` varies the gesture shape — dwell, seq, DOWN/MOVE/UP — and assumes the numbers
    themselves are right. This varies the numbers and holds the gesture fixed, because the field
    report it exists for is a *positional* error: taps on the right of the picture land far to the
    left, while the left half behaves. That asymmetry is the signature of the midpoint, and the
    midpoint is where every one of these encodings disagrees with the others.

    The app's own arithmetic has been ruled out — measured against the emulator, a tap at 75.0% of
    the picture puts 75.0% on the wire, edge to edge, on both axes. So what is left is what the
    display *does* with 0..65535, and only the display can answer it.

    Watch the cursor. One variant will put it under the crosshair you aimed at; the rest will not.
    """
    fx, fy = args.fx, args.fy
    v = mfd.version_byte
    w, h = args.width, args.height

    def wire(n: int) -> int:
        """struct packs a signed int16, so hand it the value that yields the bytes we mean."""
        return n - 0x10000 if n > 0x7FFF else n

    variants = [
        ("unsigned full-scale 0..65535  (what the app sends today)",
         wire(round(fx * 0xFFFF)), wire(round(fy * 0xFFFF))),
        ("signed, origin at centre  -32768..32767",
         round((fx - 0.5) * 0xFFFF), round((fy - 0.5) * 0xFFFF)),
        ("half-scale 0..32767  (never exceeds a positive int16)",
         round(fx * 0x7FFF), round(fy * 0x7FFF)),
        (f"raw pixels 0..{w} / 0..{h}  (not normalised at all)",
         round(fx * w), round(fy * h)),
    ]

    with Rrc(mfd) as rrc:
        print(f"\n=== touch encoding probe, aiming at {fx:.0%} across, {fy:.0%} down ===")
        print("    Aim point on an 800x480 screen: "
              f"({round(fx * w)},{round(fy * h)}). Watch where the cursor actually lands.\n")
        for i, (name, nx, ny) in enumerate(variants, start=1):
            print(f"[{i}] {name}")
            print(f"    sending x={nx} y={ny}  (bytes {struct.pack('<hh', nx, ny).hex(' ')})")
            rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_DOWN, 0, nx, ny, v), "DOWN")
            time.sleep(0.12)
            rrc._send(rrc_codec.encode_touch(rrc_codec.TOUCH_UP, 0, nx, ny, v), "UP")
            input("    -> where did the cursor land? press Enter for the next variant ... ")

    print("\nThe variant that landed on the aim point is the encoding. Report the number and the\n"
          "app's `normalise()` follows it; nothing here changes the app by itself.")
    return 0


def cmd_scan(args, mfd: Mfd) -> int:
    """Reconnaissance: what else is on the MFD's WiFi, and does it expose any data service?

    Findings from the boat's E9 on 2026-07-25 are recorded in docs/protocol-spec.md; re-run this
    if the network changes (extra MFD, a NMEA gateway added, firmware update).
    """
    import concurrent.futures as cf

    ports = [21, 22, 23, 80, 443, 502, 554, 1883, 2000, 2101, 2102, 3000, 4800, 5672,
             8080, 8554, 10001, 10110, 10111, 50000]

    def tcp(ip: str, port: int, t: float = 1.0):
        s = socket.socket(); s.settimeout(t)
        try:
            s.connect((ip, port))
            try:
                s.settimeout(1.5); banner = s.recv(160)
            except Exception:
                banner = b""
            return port, banner
        except Exception:
            return None
        finally:
            s.close()

    print(f"\n=== TCP ports on {mfd.host} ===")
    with cf.ThreadPoolExecutor(40) as ex:
        for r in ex.map(lambda p: tcp(mfd.host, p), ports):
            if r:
                p, b = r
                print(f"  OPEN {p:<6}" + (f" {b[:100]!r}" if b else ""))

    net = mfd.host.rsplit(".", 1)[0]
    print(f"\n=== hosts on {net}.0/24 ===")

    def alive(ip: str):
        for p in (22, 80, 2000, 5672, 50000, 8554):
            if tcp(ip, p, 0.35):
                return ip, p
        return None

    with cf.ThreadPoolExecutor(120) as ex:
        for r in ex.map(alive, [f"{net}.{i}" for i in range(1, 255)]):
            if r:
                note = "  <- the MFD" if r[0] == mfd.host else ""
                print(f"  {r[0]}  (tcp/{r[1]}){note}")

    print(f"\n=== listening {args.udp_secs}s for NMEA-style UDP broadcast ===")
    for port in (10110, 2000, 4800):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", port))
        except Exception as e:
            print(f"  udp/{port}: bind failed ({e})"); s.close(); continue
        s.settimeout(args.udp_secs)
        try:
            data, addr = s.recvfrom(2048)
            print(f"  udp/{port}: from {addr[0]}: {data[:120]!r}")
        except socket.timeout:
            print(f"  udp/{port}: silent")
        finally:
            s.close()
    return 0


def main() -> int:
    p = argparse.ArgumentParser(prog="mfd_ctl", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", help="skip discovery; MFD IP")
    p.add_argument("--rtsp-port", type=int, default=8554)
    p.add_argument("--rtsp-path", default="RAYMARINEMFD")
    p.add_argument("--rrc-port", type=int, default=50000)
    p.add_argument("--version", default="10", help="RRC version byte in hex (real E9 = 10)")
    p.add_argument("--interface", help="bind mDNS to this local IPv4 (multi-homed hosts)")
    p.add_argument("--timeout", type=float, default=12.0, help="discovery seconds")
    p.add_argument("--transport", default="udp", choices=("udp", "tcp"),
                   help="RTSP transport for screenshots (real MFD needs udp)")
    p.add_argument("--outdir", default="shots")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("discover")
    s = sub.add_parser("shot"); s.add_argument("--out", default=None)
    s = sub.add_parser("button"); s.add_argument("name")
    s.add_argument("--repeat", type=int, default=1, help="tap it N times")
    s.add_argument("--gap", type=float, default=0.04, help="seconds between taps")
    s.add_argument("--hold", type=float, default=None,
                   help="instead of tapping, hold the key down this many seconds")
    s.add_argument("--shot-after", default=None, help="grab a screenshot when done")
    s = sub.add_parser("pointer"); s.add_argument("x", type=int); s.add_argument("y", type=int)
    s = sub.add_parser("probe"); s.add_argument("points", nargs="+", metavar="X,Y")
    s.add_argument("--settle", type=float, default=1.0)
    s = sub.add_parser("sweep")
    s.add_argument("--axis", choices=("x", "y"), default="x")
    s.add_argument("--lo", type=int, default=0)
    s.add_argument("--hi", type=int, default=800)
    s.add_argument("--fixed", type=int, default=240)
    s.add_argument("--steps", type=int, default=5)
    s.add_argument("--settle", type=float, default=1.0)
    s = sub.add_parser("scan"); s.add_argument("--udp-secs", type=float, default=6.0)
    s = sub.add_parser("touch-probe", help="find out how a real display wants opcode-3 touch")
    s.add_argument("x", type=int, nargs="?", default=400, help="pixel x to press (default 400)")
    s.add_argument("y", type=int, nargs="?", default=240, help="pixel y to press (default 240)")
    s.add_argument("--x2", type=int, default=700, help="drag end x")
    s.add_argument("--y2", type=int, default=240, help="drag end y")
    s.add_argument("--width", type=int, default=800)
    s.add_argument("--height", type=int, default=480)
    s = sub.add_parser("touch-encoding",
                       help="find out how a real display SCALES touch coordinates")
    s.add_argument("fx", type=float, nargs="?", default=0.75,
                   help="fraction across the picture to aim at (default 0.75 — the right "
                        "quarter, where the reported error appears)")
    s.add_argument("fy", type=float, nargs="?", default=0.5, help="fraction down (default 0.5)")
    s.add_argument("--width", type=int, default=800)
    s.add_argument("--height", type=int, default=480)
    s = sub.add_parser("tap"); s.add_argument("x", type=int); s.add_argument("y", type=int)
    s.add_argument("--shot-after", default=None)
    s = sub.add_parser("swipe")
    s.add_argument("x1", type=int); s.add_argument("y1", type=int)
    s.add_argument("x2", type=int); s.add_argument("y2", type=int)
    s.add_argument("--steps", type=int, default=8)
    s.add_argument("--shot-after", default=None)

    args = p.parse_args()
    mfd = resolve(args)
    if mfd is None:
        return 2

    if args.cmd == "discover":
        return 0
    if args.cmd == "shot":
        out = Path(args.out) if args.out else Path(args.outdir) / f"shot_{time.strftime('%H%M%S')}.png"
        return 0 if screenshot(mfd, out, args.transport) else 1
    if args.cmd == "button":
        with Rrc(mfd) as rrc:
            if args.hold is not None:
                rrc.hold(args.name, args.hold)
            else:
                rrc.button(args.name, repeat=args.repeat, gap=args.gap)
        if args.shot_after:
            time.sleep(1.0)
            screenshot(mfd, Path(args.shot_after), args.transport)
        return 0
    if args.cmd == "pointer":
        with Rrc(mfd) as rrc:
            rrc.pointer(args.x, args.y)
        return 0
    if args.cmd == "probe":
        return cmd_probe(args, mfd)
    if args.cmd == "sweep":
        return cmd_sweep(args, mfd)
    if args.cmd == "scan":
        return cmd_scan(args, mfd)
    if args.cmd == "touch-probe":
        return cmd_touch_probe(args, mfd)
    if args.cmd == "touch-encoding":
        return cmd_touch_encoding(args, mfd)
    if args.cmd in ("tap", "swipe"):
        with Rrc(mfd) as rrc:
            if args.cmd == "tap":
                rrc.tap(args.x, args.y)
            else:
                rrc.swipe(args.x1, args.y1, args.x2, args.y2, steps=args.steps)
        if args.shot_after:
            time.sleep(1.2)
            screenshot(mfd, Path(args.shot_after), args.transport)
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
