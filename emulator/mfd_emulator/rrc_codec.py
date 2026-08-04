"""RRC (Raymarine Remote Control) wire codec.

Written from a protocol description — frame layout, opcode numbers and field widths — recorded
from observed traffic against a display we own. The stock client writes ``payload_len + 9``
bytes per frame to a TCP socket, and emits the magic ``"RRCE"`` *reversed*, so on the wire every
frame starts with the bytes ``E C R R``.

Frame layout (total length = ``payload_len + 9``)::

    offset  bytes         meaning
    0..3    45 43 52 52   magic "ECRR"  (= "RRCE" reversed)
    4       01            constant 0x01
    5       vv            protocol version (from the _rym_rrc TXT record; 0x01 by default)
    6       op            opcode: 01 = button, 02 = pointer/zoom, 03 = touch
    7       ll            payload length low byte  (button=0x02, pointer=0x04, touch=0x06)
    8       hh            payload length high byte (always 0x00 in practice)
    9..     ....          payload

button  (op 01, len 2): [keycode][action]                 action 1=down, 2=up
pointer (op 02, len 4): [x_lo][x_hi][y_lo][y_hi]           two little-endian int16
touch   (op 03, len 6): [action][seq][x_lo][x_hi][y_lo][y_hi]  action 1=down, 2=up, 3=move;
                        x/y normalised 0..65535 across the video area (NOT pixels)

The client never reads this socket except to detect EOF, so the server only *decodes* frames;
it never has to reply. The encoders here exist so tests and the loopback harness can put known
bytes on the wire.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

MAGIC = b"ECRR"  # "RRCE" reversed, as it appears on the wire
HEADER_LEN = 9
CONST_BYTE = 0x01

OPCODE_BUTTON = 0x01
OPCODE_POINTER = 0x02
# Opcode 3 = TOUCH. The phone remote never sends it; the tablet remote does, which is why the
# opcode is real even though a phone-only capture never shows it. Untested against hardware --
# see the touch-encoding probe in scripts/mfd_ctl.py.
OPCODE_TOUCH = 0x03

TOUCH_DOWN = 0x01
TOUCH_UP = 0x02
TOUCH_MOVE = 0x03
TOUCH_ACTION_NAMES = {TOUCH_DOWN: "down", TOUCH_UP: "up", TOUCH_MOVE: "move"}

# Touch coordinates are NOT MFD pixels: they are normalised to 0..0xFFFF across the video
# area, which is why they fit an int16 and why the protocol is resolution-independent. The
# denominator is the picture's own width and height -- the letterboxed area actually showing
# video, not the whole view -- and a point outside it is dropped rather than clamped.
TOUCH_MAX = 0xFFFF

ACTION_DOWN = 0x01
ACTION_UP = 0x02

ACTION_NAMES = {ACTION_DOWN: "down", ACTION_UP: "up"}

# Windows Virtual-Key codes the protocol carries, mapped to the control each one drives.
# These are the device's own key numbering, reproduced because the wire format requires it.
KEYCODES: dict[int, str] = {
    37: "LEFT",        # 0x25  joystick drag
    38: "UP",          # 0x26  joystick drag
    39: "RIGHT",       # 0x27  joystick drag
    40: "DOWN",        # 0x28  joystick drag
    13: "OK/ENTER",    # 0x0D  joystick tap / long-press release
    118: "HOME",       # 0x76  F7
    120: "MENU",       # 0x78  F9
    27: "BACK",        # 0x1B  ESC
    33: "RANGE_OUT",   # 0x21  PageUp
    34: "RANGE_IN",    # 0x22  PageDown
    122: "SWITCH",     # 0x7A  F11  (active pane / switch)
    119: "WPT",        # 0x77  F8   (waypoint / MOB)
}


def key_name(keycode: int) -> str:
    """Symbolic name for a keycode, or ``VK_<n>`` if unknown."""
    return KEYCODES.get(keycode, f"VK_{keycode}")


@dataclass(frozen=True)
class ButtonEvent:
    keycode: int
    action: int
    version: int

    @property
    def name(self) -> str:
        return key_name(self.keycode)

    @property
    def action_name(self) -> str:
        return ACTION_NAMES.get(self.action, f"action_{self.action}")

    def __str__(self) -> str:  # oracle log form
        return f"button  {self.name:<11} {self.action_name}"


@dataclass(frozen=True)
class PointerEvent:
    x: int
    y: int
    version: int

    def __str__(self) -> str:
        return f"pointer cursor      dx={self.x:+d} dy={self.y:+d}"


@dataclass(frozen=True)
class TouchEvent:
    action: int
    seq: int
    x: int
    y: int
    version: int

    @property
    def action_name(self) -> str:
        return TOUCH_ACTION_NAMES.get(self.action, f"action_{self.action}")

    @property
    def fraction(self) -> tuple[float, float]:
        """Position as (fx, fy) in 0.0..1.0 across the video area."""
        return (self._unsigned(self.x) / TOUCH_MAX, self._unsigned(self.y) / TOUCH_MAX)

    @staticmethod
    def _unsigned(v: int) -> int:
        # The wire field is a signed int16 but the value space is 0..65535, so the
        # top half arrives negative. Undo that before treating it as a fraction.
        return v + 0x10000 if v < 0 else v

    def __str__(self) -> str:  # oracle log form
        fx, fy = self.fraction
        return (f"touch   {self.action_name:<11} seq={self.seq:<3} "
                f"norm=({self._unsigned(self.x)},{self._unsigned(self.y)}) "
                f"= {fx * 100:.1f}%,{fy * 100:.1f}%  (800x480 -> {round(fx * 800)},{round(fy * 480)})")


@dataclass(frozen=True)
class UnknownEvent:
    """A well-framed message we could not interpret (e.g. an opcode we have not mapped)."""

    reason: str
    raw: bytes

    def __str__(self) -> str:
        return f"?????   {self.reason} raw={self.raw.hex(' ')}"


class ProtocolError(ValueError):
    """Raised when bytes on the RRC socket do not parse as a valid frame."""


# --------------------------------------------------------------------------- encoders


def encode_button(keycode: int, action: int, version: int = 0x01) -> bytes:
    """One key press or release: opcode 1, payload ``[keycode][action]``."""
    return bytes(
        [MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3],
         CONST_BYTE, version & 0xFF, OPCODE_BUTTON, 0x02, 0x00,
         keycode & 0xFF, action & 0xFF]
    )


def encode_touch(action: int, seq: int, x: int, y: int, version: int = 0x01) -> bytes:
    """One touch event: opcode 3, a 15-byte frame.

    Layout (payload len 6): ``[action][seq][x_lo][x_hi][y_lo][y_hi]``

    * ``action`` -- 1 DOWN, 2 UP, 3 MOVE (see TOUCH_*).
    * ``seq``    -- move counter, zero on DOWN/UP and incrementing on each MOVE within a drag,
      so it is a per-gesture sequence number, not a pointer id.
    * ``x``/``y`` -- **normalised 0..65535 across the video area**, little-endian int16
      (see TOUCH_MAX). Not pixels.
    """
    header = bytes([MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3],
                    CONST_BYTE, version & 0xFF, OPCODE_TOUCH, 0x06, 0x00])
    return header + bytes([action & 0xFF, seq & 0xFF]) + struct.pack("<hh", x, y)


def norm_xy(x_px: int, y_px: int, width: int, height: int) -> tuple[int, int]:
    """Map pixel coords in a width x height image to the protocol's 0..65535 touch space."""
    nx = max(0, min(TOUCH_MAX, round(x_px / max(1, width) * TOUCH_MAX)))
    ny = max(0, min(TOUCH_MAX, round(y_px / max(1, height) * TOUCH_MAX)))
    # struct packs signed int16, and the field is a signed 16-bit value -- so wrap the top half.
    return (nx - 0x10000 if nx > 0x7FFF else nx,
            ny - 0x10000 if ny > 0x7FFF else ny)


def encode_pointer(x: int, y: int, version: int = 0x01) -> bytes:
    """Zoom/pointer: opcode 2, payload two little-endian int16 (x, y)."""
    header = bytes(
        [MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3],
         CONST_BYTE, version & 0xFF, OPCODE_POINTER, 0x04, 0x00]
    )
    return header + struct.pack("<hh", x, y)


# --------------------------------------------------------------------------- decoding


def frame_length(header: bytes) -> int:
    """Total frame length given at least the 9-byte header, per ``ao``'s ``bArr[7] + 9``.

    Length is taken as a 16-bit little-endian value at [7:9] (high byte is always 0),
    which reduces to ``header[7] + 9`` in practice.
    """
    if len(header) < HEADER_LEN:
        raise ValueError("need the full 9-byte header to compute frame length")
    payload_len = header[7] | (header[8] << 8)
    return HEADER_LEN + payload_len


def decode_frame(buf: bytes) -> ButtonEvent | PointerEvent | TouchEvent:
    """Decode one complete frame. ``buf`` must be exactly one frame."""
    if len(buf) < HEADER_LEN:
        raise ProtocolError(f"short frame: {len(buf)} bytes")
    if buf[:4] != MAGIC:
        raise ProtocolError(f"bad magic: {buf[:4]!r} (expected {MAGIC!r})")
    version = buf[5]
    opcode = buf[6]
    payload = buf[HEADER_LEN:]
    if opcode == OPCODE_BUTTON:
        if len(payload) < 2:
            raise ProtocolError("button frame too short")
        return ButtonEvent(keycode=payload[0], action=payload[1], version=version)
    if opcode == OPCODE_POINTER:
        if len(payload) < 4:
            raise ProtocolError("pointer frame too short")
        x, y = struct.unpack("<hh", payload[:4])
        return PointerEvent(x=x, y=y, version=version)
    if opcode == OPCODE_TOUCH:
        if len(payload) < 6:
            raise ProtocolError("touch frame too short")
        x, y = struct.unpack("<hh", payload[2:6])
        return TouchEvent(action=payload[0], seq=payload[1], x=x, y=y, version=version)
    raise ProtocolError(f"unknown opcode: {opcode}")


class RrcFrameParser:
    """Accumulate TCP bytes and yield complete decoded events.

    Tolerant of partial frames (spanning TCP segments) and multiple frames per read.
    Resyncs on a bad magic by dropping one byte, so a corrupt stream cannot wedge it.
    """

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[ButtonEvent | PointerEvent | TouchEvent]:
        self._buf.extend(data)
        events: list[ButtonEvent | PointerEvent | TouchEvent] = []
        while True:
            if len(self._buf) < HEADER_LEN:
                break
            if bytes(self._buf[:4]) != MAGIC:
                # Resync: drop one byte and keep scanning for the magic.
                del self._buf[0]
                continue
            total = frame_length(bytes(self._buf[:HEADER_LEN]))
            if len(self._buf) < total:
                break  # wait for the rest of the frame
            frame = bytes(self._buf[:total])
            del self._buf[:total]
            try:
                events.append(decode_frame(frame))
            except ProtocolError as e:
                # The frame length was valid, so the stream is still in sync — surface the
                # oddity and keep going rather than dropping the client. This is what makes
                # the emulator usable as a probe for opcodes we have not reverse-engineered.
                events.append(UnknownEvent(reason=str(e), raw=frame))
        return events
