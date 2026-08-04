"""Generate a synthetic "chartplotter" test video: a stylized boat sailing on Lake Erie.

Renders frames with Pillow (dark nautical-chart background, depth contours, a moving boat icon
with a wake trail, a compass rose, and a chartplotter-style data HUD) and pipes them into FFmpeg
to encode H.264. The whole animation is designed as a mathematically EXACT loop — the boat's path
and every HUD value are periodic functions of frame-index-mod-total, so frame N and frame 0 are
continuous — so `-stream_loop -1` in video.py never shows a visible seam.

Usage:
    .venv/Scripts/python.exe scripts/generate_chartplotter_video.py [--seconds 30] [--fps 15]

Output: assets/lake_erie_chartplotter.mp4 (800x480, matches the app's fixed VideoDecoder buffer
size observed in logcat: "VideoDecoder initialised 800x480"). Point mfd.yaml's `rtsp.source` at
`loop:<absolute path>` to serve it instead of the default synthetic testsrc.
"""

from __future__ import annotations

import argparse
import math
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 800, 480

# Nautical chart palette (dark, Raymarine/Garmin-night-mode inspired).
COLOR_WATER_DEEP = (6, 22, 38)
COLOR_WATER_MID = (9, 33, 54)
COLOR_WATER_SHALLOW = (13, 48, 74)
COLOR_CONTOUR = (40, 90, 120)
COLOR_GRID = (24, 52, 70)
COLOR_TRACK = (255, 200, 40)
COLOR_BOAT = (255, 255, 255)
COLOR_HUD_BG = (4, 14, 24)
COLOR_HUD_BORDER = (60, 120, 150)
COLOR_HUD_TEXT = (140, 230, 255)
COLOR_HUD_LABEL = (90, 150, 175)
COLOR_TITLE = (200, 235, 250)
COLOR_COMPASS = (80, 150, 175)

# Lake Erie's rough center, used to synthesize a plausible-looking lat/lon readout.
LAKE_ERIE_LAT, LAKE_ERIE_LON = 42.20, -81.20

FONT_DIR = Path("C:/Windows/Fonts")


def _font(name: str, size: int) -> ImageFont.FreeTypeFont:
    path = FONT_DIR / name
    if path.exists():
        return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def draw_background(draw: ImageDraw.ImageDraw, t: float) -> None:
    """Vertical water gradient + a few wavy depth-contour lines + a faint lat/lon grid."""
    for y in range(H):
        f = y / H
        r = int(COLOR_WATER_DEEP[0] + (COLOR_WATER_SHALLOW[0] - COLOR_WATER_DEEP[0]) * f)
        g = int(COLOR_WATER_DEEP[1] + (COLOR_WATER_SHALLOW[1] - COLOR_WATER_DEEP[1]) * f)
        b = int(COLOR_WATER_DEEP[2] + (COLOR_WATER_SHALLOW[2] - COLOR_WATER_DEEP[2]) * f)
        draw.line([(0, y), (W, y)], fill=(r, g, b))

    # Faint lat/lon grid (static; a real chart wouldn't scroll the graticule with the vessel
    # at this zoom level, which also keeps the loop trivially seamless).
    for gx in range(0, W, 80):
        draw.line([(gx, 0), (gx, H)], fill=COLOR_GRID, width=1)
    for gy in range(0, H, 80):
        draw.line([(0, gy), (W, gy)], fill=COLOR_GRID, width=1)

    # Depth contour bands: gentle sine-wave bathymetry lines, static (no dependency on t) so the
    # loop is exact.
    for i, amp in enumerate((30, 55, 85)):
        pts = []
        for x in range(0, W + 10, 10):
            y = 140 + i * 90 + amp * math.sin(x / 140.0 + i * 1.3)
            pts.append((x, y))
        draw.line(pts, fill=COLOR_CONTOUR, width=2)


def boat_position(frac: float) -> tuple[float, float, float]:
    """Boat (x, y, heading_deg) at loop-fraction frac in [0,1). A closed Lissajous-ish track
    that stays clear of the HUD boxes and returns exactly to its start each loop."""
    cx, cy = W * 0.52, H * 0.56
    rx, ry = W * 0.30, H * 0.26
    ang = frac * 2 * math.pi
    x = cx + rx * math.cos(ang)
    y = cy + ry * math.sin(ang * 1.0) * math.sin(ang * 0.5 + 0.3) * 1.3
    # Heading = direction of travel (numerical tangent), converted to compass degrees
    # (0=N, 90=E), matching typical chartplotter COG convention.
    d = 1e-3
    x2 = cx + rx * math.cos(ang + d)
    y2 = cy + ry * math.sin((ang + d) * 1.0) * math.sin((ang + d) * 0.5 + 0.3) * 1.3
    heading = (math.degrees(math.atan2(x2 - x, -(y2 - y)))) % 360
    return x, y, heading


def draw_wake(draw: ImageDraw.ImageDraw, frac: float, trail_frac: float = 0.12) -> None:
    """A fading trail behind the boat along the same closed path (always the PRECEDING
    trail_frac of the loop), so it's correct on every frame including wrap-around."""
    steps = 40
    pts = []
    for i in range(steps + 1):
        f = (frac - trail_frac * i / steps) % 1.0
        x, y, _ = boat_position(f)
        pts.append((x, y))
    for i in range(len(pts) - 1):
        alpha = 1.0 - (i / len(pts))
        width = max(1, int(3 * alpha))
        draw.line([pts[i], pts[i + 1]], fill=COLOR_TRACK, width=width)


def draw_boat(draw: ImageDraw.ImageDraw, x: float, y: float, heading_deg: float) -> None:
    """A simple triangular vessel icon pointing along its heading."""
    size = 14
    rad = math.radians(heading_deg)
    # Triangle points: nose, left-rear, right-rear, in boat-local space (nose = +y_local).
    local = [(0, -size), (-size * 0.55, size * 0.7), (size * 0.55, size * 0.7)]
    pts = []
    for lx, ly in local:
        rx = lx * math.cos(rad) - ly * math.sin(rad)
        ry = lx * math.sin(rad) + ly * math.cos(rad)
        pts.append((x + rx, y + ry))
    draw.polygon(pts, fill=COLOR_BOAT, outline=(255, 230, 120))


def draw_compass(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, heading_deg: float,
                  font: ImageFont.FreeTypeFont) -> None:
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=COLOR_COMPASS, width=2)
    for label, ang in (("N", 0), ("E", 90), ("S", 180), ("W", 270)):
        rad = math.radians(ang)
        lx = cx + (r - 14) * math.sin(rad)
        ly = cy - (r - 14) * math.cos(rad)
        draw.text((lx, ly), label, fill=COLOR_COMPASS, font=font, anchor="mm")
    rad = math.radians(heading_deg)
    tip = (cx + (r - 6) * math.sin(rad), cy - (r - 6) * math.cos(rad))
    draw.line([(cx, cy), tip], fill=COLOR_TRACK, width=3)
    draw.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=COLOR_TRACK)


def data_box(draw: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, label: str, value: str,
             font_label: ImageFont.FreeTypeFont, font_value: ImageFont.FreeTypeFont) -> None:
    draw.rectangle([x, y, x + w, y + h], fill=COLOR_HUD_BG, outline=COLOR_HUD_BORDER, width=1)
    draw.text((x + 6, y + 4), label, fill=COLOR_HUD_LABEL, font=font_label)
    draw.text((x + 6, y + h - 24), value, fill=COLOR_HUD_TEXT, font=font_value)


def lat_lon_at(frac: float) -> tuple[float, float]:
    x, y, _ = boat_position(frac)
    # Map screen position to a small lat/lon offset around Lake Erie's center.
    lat = LAKE_ERIE_LAT + (H / 2 - y) * 0.00006
    lon = LAKE_ERIE_LON + (x - W / 2) * 0.00008
    return lat, lon


def render_frame(frac: float, fonts: dict) -> Image.Image:
    img = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(img)
    draw_background(draw, frac)

    x, y, heading = boat_position(frac)
    draw_wake(draw, frac)
    draw_boat(draw, x, y, heading)

    # Title bar.
    draw.rectangle([0, 0, W, 26], fill=COLOR_HUD_BG)
    draw.text((10, 4), "LAKE ERIE", fill=COLOR_TITLE, font=fonts["title"])
    draw.text((W - 150, 4), "CHART  1:24000", fill=COLOR_HUD_LABEL, font=fonts["small"])

    # Data HUD (bottom strip): SOG / COG / DPT / position — periodic in frac, exact loop.
    sog = 6.2 + 1.4 * math.sin(frac * 2 * math.pi * 2)
    depth = 38.0 + 9.0 * math.sin(frac * 2 * math.pi * 1.5 + 1.0)
    lat, lon = lat_lon_at(frac)

    box_w, box_h, gap, y0 = 130, 54, 8, H - 62
    labels = [
        ("SOG", f"{sog:4.1f} kn"),
        ("COG", f"{heading:5.1f}°M"),
        ("DPT", f"{depth:4.1f} ft"),
        ("POSN", f"{lat:.4f}N"),
    ]
    for i, (lbl, val) in enumerate(labels):
        data_box(draw, 10 + i * (box_w + gap), y0, box_w, box_h, lbl, val,
                  fonts["label"], fonts["value"])
    draw.text((10 + 4 * (box_w + gap), y0 + box_h - 24), f"{lon:.4f}W",
              fill=COLOR_HUD_TEXT, font=fonts["value"])

    draw_compass(draw, W - 55, 70, 40, heading, fonts["small"])
    return img


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=30)
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--ffmpeg", default=None)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    ffmpeg = args.ffmpeg or shutil.which("ffmpeg")
    if not ffmpeg:
        winget_default = ("C:/Users/pdrak/AppData/Local/Microsoft/WinGet/Packages/"
                           "Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/"
                           "ffmpeg-8.1.2-full_build/bin/ffmpeg.exe")
        if Path(winget_default).exists():
            ffmpeg = winget_default
        else:
            print("ffmpeg not found; pass --ffmpeg <path> or install it "
                  "(scripts/install_media_tools.ps1)", file=sys.stderr)
            return 1

    total_frames = args.seconds * args.fps
    out_dir = Path(__file__).resolve().parent.parent / "assets"
    out_dir.mkdir(exist_ok=True)
    out_path = Path(args.out) if args.out else out_dir / "lake_erie_chartplotter.mp4"

    fonts = {
        "title": _font("consolab.ttf", 16) if (FONT_DIR / "consolab.ttf").exists() else _font("consola.ttf", 16),
        "small": _font("consola.ttf", 12),
        "label": _font("consola.ttf", 11),
        "value": _font("consolab.ttf", 16) if (FONT_DIR / "consolab.ttf").exists() else _font("consola.ttf", 16),
    }

    cmd = [
        ffmpeg, "-y", "-hide_banner", "-loglevel", "warning",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(args.fps),
        "-i", "-",
        "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p",
        "-g", str(args.fps * 2), "-b:v", "2M",
        str(out_path),
    ]
    print(f"Rendering {total_frames} frames ({args.seconds}s @ {args.fps}fps, {W}x{H}) -> {out_path}")
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    assert proc.stdin is not None
    try:
        for i in range(total_frames):
            frac = i / total_frames
            frame = render_frame(frac, fonts)
            proc.stdin.write(frame.tobytes())
            if i % (args.fps * 5) == 0:
                print(f"  frame {i}/{total_frames}")
    finally:
        proc.stdin.close()
        proc.wait()

    if proc.returncode != 0:
        print("ffmpeg encode failed", file=sys.stderr)
        return 1
    print(f"Done -> {out_path} ({out_path.stat().st_size / 1024:.0f} KB)")
    print(f"Set emulator/mfd.yaml rtsp.source to: loop:{out_path.as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
