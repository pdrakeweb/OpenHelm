"""Reconnaissance for the MFD's AMQP broker — the unexplored lead on TCP 5672.

The MFD's Wi-Fi is an isolated AP with no NMEA bridge, so the only candidate for structured data
(position, depth, autopilot state) is the AMQP 0-10 broker it listens with on TCP 5672. Nothing
about it has been probed. This script is the first pass, designed to be run once at the boat and
to capture everything a later session needs to decide whether the lead is worth pursuing.

It deliberately uses no AMQP library: the goal of pass one is to record exactly what the broker
says, not to speak fluent AMQP. Every exchange is hexdumped to a file.

    python scripts/probe_amqp.py                      # default 192.168.131.1:5672
    python scripts/probe_amqp.py --host 10.0.0.5

What it tries, each on a fresh connection:

1. silence      — connect and say nothing. Some brokers greet first; record it if this one does.
2. AMQP 0-10    — the protocol header for the version the port was fingerprinted as. A 0-10
                  broker answers with its own header followed by connection.start, which carries
                  the server's supported SASL mechanisms and locales in a field table — often the
                  single most identifying thing a broker says.
3. AMQP 0-9-1   — RabbitMQ and most general-purpose brokers speak this. If the MFD's broker does
                  too, a mature client library exists and the lead gets much cheaper.
4. AMQP 1.0     — the ISO version; a different framing entirely. Cheap to rule in or out.

For each attempt the script records: whether the connection was accepted at all, the raw bytes
the broker sent back (hexdumped with ASCII gutter), and a one-line interpretation of the reply's
protocol header if it looks like one. Nothing is interpreted beyond that header — deeper parsing
is pass two, once we know which dialect answers.

Output goes to stdout and, with --out, to a file for the later session to read.

This is safe, read-mostly reconnaissance: it opens a TCP connection, sends at most a fixed
protocol header, reads what comes back, and closes. It authenticates nothing and publishes
nothing.
"""

from __future__ import annotations

import argparse
import datetime as dt
import socket
import sys
from dataclasses import dataclass

# A field diagnostic must never die on a console's encoding. Force UTF-8 where the platform lets
# us; the output is plain ASCII anyway, so this is belt and braces.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass


# AMQP protocol headers are 8 bytes: "AMQP" + four version-identifying bytes. The broker either
# answers with a frame (it accepts the dialect) or replies with its *own* 8-byte header naming the
# version it wants and hangs up (classic version negotiation), or just drops the connection.
PROTOCOL_HEADERS: dict[str, bytes] = {
    # 0-10: "AMQP" 0x01 0x01 0x00 0x0A  (class 1, instance 1, major 0, minor 10)
    "amqp-0-10": b"AMQP\x01\x01\x00\x0a",
    # 0-9-1: "AMQP" 0x00 0x00 0x09 0x01
    "amqp-0-9-1": b"AMQP\x00\x00\x09\x01",
    # 0-9:   "AMQP" 0x01 0x01 0x00 0x09
    "amqp-0-9": b"AMQP\x01\x01\x00\x09",
    # 0-8:   "AMQP" 0x01 0x01 0x08 0x00
    "amqp-0-8": b"AMQP\x01\x01\x08\x00",
    # 1.0:   "AMQP" 0x00 0x01 0x00 0x00
    "amqp-1-0": b"AMQP\x00\x01\x00\x00",
}


@dataclass
class Attempt:
    name: str
    sent: bytes
    accepted: bool = False
    received: bytes = b""
    note: str = ""
    error: str = ""


def hexdump(data: bytes, width: int = 16) -> str:
    """Classic offset / hex / ASCII hexdump, so the record is readable by eye."""
    if not data:
        return "    (no bytes)"
    lines = []
    for off in range(0, len(data), width):
        chunk = data[off:off + width]
        hex_part = " ".join(f"{b:02x}" for b in chunk)
        hex_part = f"{hex_part:<{width * 3 - 1}}"
        ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"    {off:04x}  {hex_part}  |{ascii_part}|")
    return "\n".join(lines)


def interpret_header(data: bytes) -> str:
    """If the reply opens with an AMQP protocol header, name the version it points at."""
    if len(data) >= 8 and data[:4] == b"AMQP":
        v = data[4:8]
        for name, header in PROTOCOL_HEADERS.items():
            if header[4:8] == v:
                return f"broker replied with the {name} protocol header — it wants that dialect"
        return f"broker replied with an AMQP header, version bytes {v.hex(' ')} (unrecognised)"
    if len(data) >= 7 and data[0] == 0x01:
        # AMQP 0-10 frames start 0x01 (frame format 1); a connection.start is the usual first.
        return "reply looks like an AMQP 0-10 frame (0x01…) — likely connection.start"
    if len(data) >= 8 and data[0] == 0x00:
        return "reply looks like an AMQP 1.0 frame (starts 0x00 00 00 …)"
    return "reply is not an AMQP protocol header — record and interpret in pass two"


def run_attempt(host: str, port: int, name: str, payload: bytes, timeout: float) -> Attempt:
    attempt = Attempt(name=name, sent=payload)
    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            attempt.accepted = True
            sock.settimeout(timeout)
            if payload:
                sock.sendall(payload)
            chunks = []
            try:
                while True:
                    buf = sock.recv(4096)
                    if not buf:
                        break
                    chunks.append(buf)
                    # One AMQP frame is plenty for a fingerprint; do not wait for a stream.
                    if sum(len(c) for c in chunks) >= 4096:
                        break
            except socket.timeout:
                pass
            attempt.received = b"".join(chunks)
            attempt.note = (
                interpret_header(attempt.received)
                if attempt.received
                else "connection accepted, broker said nothing (it may expect the client to speak first)"
            )
    except ConnectionRefusedError:
        attempt.error = "connection refused — nothing is listening on this port"
    except socket.timeout:
        attempt.error = f"timed out after {timeout:.0f}s (no connection, or accepted then silent)"
    except OSError as e:
        attempt.error = f"{type(e).__name__}: {e}"
    return attempt


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--host", default="192.168.131.1", help="MFD address (default: the E9's)")
    ap.add_argument("--port", type=int, default=5672, help="AMQP port (default 5672)")
    ap.add_argument("--timeout", type=float, default=5.0, help="per-attempt timeout, seconds")
    ap.add_argument("--out", help="also write the full record to this file")
    args = ap.parse_args()

    report: list[str] = []

    def emit(line: str = "") -> None:
        print(line)
        report.append(line)

    stamp = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    emit(f"AMQP probe - {args.host}:{args.port} - {stamp}")
    emit("=" * 72)

    # Attempt 1: say nothing, see if the broker greets.
    plan: list[tuple[str, bytes]] = [("silence (listen only)", b"")]
    # Attempts 2..n: offer each dialect's protocol header, most-likely first.
    for name in ("amqp-0-10", "amqp-0-9-1", "amqp-1-0", "amqp-0-8"):
        plan.append((name, PROTOCOL_HEADERS[name]))

    any_accepted = False
    for name, payload in plan:
        emit()
        emit(f"-- {name} --")
        if payload:
            emit(f"   sent: {payload.hex(' ')}  ({payload!r})")
        attempt = run_attempt(args.host, args.port, name, payload, args.timeout)
        any_accepted = any_accepted or attempt.accepted
        if attempt.error:
            emit(f"   RESULT: {attempt.error}")
            # If the very first connection is refused, the rest will be too — fail fast but
            # still record what we tried.
            if not attempt.accepted and "refused" in attempt.error:
                emit()
                emit("   Broker not reachable. Check: on the MFD's Wi-Fi? correct --host? "
                     "(the app's discovered IP is the right one.)")
                break
            continue
        emit(f"   connection: accepted")
        emit(f"   received {len(attempt.received)} bytes:")
        emit(hexdump(attempt.received))
        emit(f"   reading: {attempt.note}")

    emit()
    emit("=" * 72)
    if any_accepted:
        emit("Next step: hand this record to a follow-up session. Whichever dialect drew an AMQP")
        emit("reply is the one to parse — a connection.start's SASL mechanisms and any server")
        emit("properties table are the first things worth decoding.")
    else:
        emit("Nothing accepted a connection. Either the port is closed on this unit, or this is")
        emit("not the MFD's address. Confirm with: python scripts/find_mfd.py")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write("\n".join(report) + "\n")
        print(f"\n(record written to {args.out})", file=sys.stderr)


if __name__ == "__main__":
    main()
