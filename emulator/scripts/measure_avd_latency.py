"""Measure how far behind live the app's picture is, on the AVD, using the `clock` source.

Method
------
The `clock` source burns frame *n* into frame *n*, and FFmpeg publishes with ``-re`` (real time),
so the frame the source is generating at any moment is simply::

    source_frame = (now - publish_start) * fps

Screenshot the AVD, note the wall-clock instant of the grab, and the difference between that
computed source frame and the frame actually on screen is the end-to-end latency::

    latency_seconds = (source_frame - displayed_frame) / fps

This needs no second video client and no clock synchronisation -- only the emulator's own log.

Accuracy
--------
``publish_start`` is taken from the emulator log, which records when FFmpeg was *spawned*, a
little before it emits frame 0. Measured against a reference client that offset is ~0.3 s, and it
biases the result **high** (reports slightly more latency than is real). So treat the output as
+-0.5 s. That is plenty to separate "a few hundred ms" from "several seconds", which is the only
distinction any of this needs to support.

The displayed frame number is read off the screenshot by eye -- deliberately. OCR would add a
failure mode to a diagnostic whose entire job is to be trustworthy.

Usage
-----
    python scripts/measure_avd_latency.py --log emu-hls.log
    -> saves a screenshot and prints the source frame at grab time; read the big number off the
       screenshot and subtract.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import subprocess
import sys
from pathlib import Path


def publish_start(log: Path) -> dt.datetime:
    """Wall-clock time FFmpeg began publishing, from the emulator's own log."""
    lines = [ln for ln in log.read_text(encoding="utf-8", errors="replace").splitlines()
             if "FFmpeg publishing" in ln]
    if not lines:
        sys.exit(f"no 'FFmpeg publishing' line in {log} -- is the emulator running with video on?")
    stamp = lines[-1].split()[0]
    today = dt.date.today()
    return dt.datetime.combine(today, dt.datetime.strptime(stamp, "%H:%M:%S.%f").time())


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--log", default="emu-hls.log", help="emulator log file")
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--out", default="avd_latency.png")
    ap.add_argument("--adb", default=None)
    args = ap.parse_args()

    adb = args.adb or os.path.expandvars(
        r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
    start = publish_start(Path(args.log))

    out = Path(args.out)
    with out.open("wb") as f:
        subprocess.run([adb, "exec-out", "screencap", "-p"], stdout=f, check=True)
    grabbed = dt.datetime.now()

    elapsed = (grabbed - start).total_seconds()
    source_frame = int(elapsed * args.fps)

    print(f"publish start   {start.time()}")
    print(f"screenshot at   {grabbed.time()}   (+{elapsed:.2f}s)")
    print(f"SOURCE frame    {source_frame}")
    print(f"screenshot      {out.resolve()}")
    print()
    print("Read the big number off the screenshot, then:")
    print(f"    latency = ({source_frame} - <displayed>) / {args.fps}  seconds")


if __name__ == "__main__":
    main()
