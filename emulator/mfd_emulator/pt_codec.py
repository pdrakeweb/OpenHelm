"""Pan/Tilt "slew" (``_rym_pt._udp``) wire codec.

Written from a protocol description, like the RRC codec beside it. **The slew feature is out
of scope for v1** (decision Q6) — this codec and its tests exist so the future phase is a
drop-in. Nothing in the running emulator advertises ``_rym_pt._udp`` or opens a UDP socket yet.

Header (8 bytes)::

    offset  bytes         meaning
    0..3    50 54 30 30   magic "PT00"
    4       01            version major
    5       00            version minor
    6       op            opcode: 01 = slew, 02 = subscribe, 03 = status
    7       ll            payload length
    8..     ....          payload

- subscribe (op 02, len 0):  client -> MFD unicast, sent right after the UDP sender opens.
- slew      (op 01, len 8):  client -> MFD unicast; azimuth then elevation, each a big-endian
                             int32 equal to ``radians * 1e8`` (both clamped to [0, 2*pi]).
- status    (op 03, len 1):  MFD -> clients multicast; payload[0] == 1 means camera online.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass

MAGIC = b"PT00"
HEADER_LEN = 8
VERSION_MAJOR = 0x01
VERSION_MINOR = 0x00

OPCODE_SLEW = 0x01
OPCODE_SUBSCRIBE = 0x02
OPCODE_STATUS = 0x03

ANGLE_SCALE = 1.0e8  # radians * 1e8, per a/i.java


class ProtocolError(ValueError):
    """Raised when a datagram does not parse as a valid PT00 message."""


@dataclass(frozen=True)
class SubscribeMessage:
    def __str__(self) -> str:
        return "subscribe"


@dataclass(frozen=True)
class SlewCommand:
    azimuth_rad: float
    elevation_rad: float

    @property
    def azimuth_deg(self) -> float:
        return math.degrees(self.azimuth_rad)

    @property
    def elevation_deg(self) -> float:
        return math.degrees(self.elevation_rad)

    def __str__(self) -> str:
        return f"slew    az={self.azimuth_deg:.1f}° el={self.elevation_deg:.1f}°"


@dataclass(frozen=True)
class StatusMessage:
    camera_online: bool

    def __str__(self) -> str:
        return f"status  camera={'online' if self.camera_online else 'offline'}"


def _header(opcode: int, payload_len: int) -> bytes:
    return bytes([*MAGIC, VERSION_MAJOR, VERSION_MINOR, opcode, payload_len])


# --------------------------------------------------------------------------- encoders


def encode_subscribe() -> bytes:
    """Reproduce ``a.i.a()`` — opcode 2, no payload (8 bytes)."""
    return _header(OPCODE_SUBSCRIBE, 0)


def encode_slew(azimuth_rad: float, elevation_rad: float) -> bytes | None:
    """Reproduce ``a.i.a(float,float)`` — returns None if out of [0, 2*pi], like the client."""
    two_pi = 6.2831855  # exact float32 literal the client compares against
    if not (0.0 <= azimuth_rad <= two_pi) or not (0.0 <= elevation_rad <= two_pi):
        return None
    az = int(azimuth_rad * ANGLE_SCALE)  # Java (int) truncates toward zero; so does Python int()
    el = int(elevation_rad * ANGLE_SCALE)
    return _header(OPCODE_SLEW, 8) + struct.pack(">ii", az, el)


def encode_status(camera_online: bool) -> bytes:
    """Reproduce ``a.i.b()`` template with the online byte set (op 3, len 1, 9 bytes)."""
    return _header(OPCODE_STATUS, 1) + bytes([1 if camera_online else 0])


# --------------------------------------------------------------------------- decoding


def decode(buf: bytes) -> SubscribeMessage | SlewCommand | StatusMessage:
    if len(buf) < HEADER_LEN:
        raise ProtocolError(f"short datagram: {len(buf)} bytes")
    if buf[:4] != MAGIC:
        raise ProtocolError(f"bad magic: {buf[:4]!r}")
    opcode = buf[6]
    payload = buf[HEADER_LEN:]
    if opcode == OPCODE_SUBSCRIBE:
        return SubscribeMessage()
    if opcode == OPCODE_SLEW:
        if len(payload) < 8:
            raise ProtocolError("slew payload too short")
        az_raw, el_raw = struct.unpack(">ii", payload[:8])
        return SlewCommand(az_raw / ANGLE_SCALE, el_raw / ANGLE_SCALE)
    if opcode == OPCODE_STATUS:
        if len(payload) < 1:
            raise ProtocolError("status payload too short")
        return StatusMessage(camera_online=payload[0] == 1)
    raise ProtocolError(f"unknown opcode: {opcode}")
