"""fault — trigger emulator faults from a script.

Thin client for the emulator's fault-control server (``mfd_emulator/control.py``). Lets an
automated test drive the same failures the interactive console menu offers, while the emulator
runs headless in the background:

    python scripts/fault.py status
    python scripts/fault.py close-rrc          # transient control drop (listener stays up)
    python scripts/fault.py rrc-down|rrc-up    # control refused entirely (display gone)
    python scripts/fault.py stall-on|stall-off # hung display: socket open, frames ignored
    python scripts/fault.py stream-drop|stream-resume  # RTSP session starves (endpoint up)
    python scripts/fault.py video-down|video-up        # RTSP endpoint refused
    python scripts/fault.py net-down|net-up    # everything at once
    python scripts/fault.py log 40             # tail the emulator's event log

Exit code 0 on ``ok``, 1 on ``err``/no connection.
"""

from __future__ import annotations

import argparse
import socket
import sys


def main() -> int:
    p = argparse.ArgumentParser(prog="fault", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--port", type=int, default=8571, help="control port (default 8571)")
    p.add_argument("command", help="fault command (see module docstring)")
    p.add_argument("args", nargs="*", help="command arguments (e.g. a line count for 'log')")
    a = p.parse_args()

    line = " ".join([a.command, *a.args]) + "\n"
    try:
        with socket.create_connection(("127.0.0.1", a.port), timeout=5) as s:
            s.sendall(line.encode())
            s.shutdown(socket.SHUT_WR)
            data = b""
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                data = data + chunk
    except OSError as e:
        print(f"err cannot reach the emulator's control port 127.0.0.1:{a.port} ({e})",
              file=sys.stderr)
        return 1

    text = data.decode("utf-8", errors="replace")
    print(text, end="" if text.endswith("\n") else "\n")
    return 0 if text.rstrip().splitlines() and text.rstrip().splitlines()[-1] == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(main())
