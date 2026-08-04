"""RRC codec conformance — encoders must reproduce the exact bytes the client emits (ad.java),
and the parser must decode them back."""

from mfd_emulator import rrc_codec as rc


def test_button_bytes_match_client_menu_down():
    # A MENU key-down (F9 = 120) at protocol version 0x01.
    expected = bytes([0x45, 0x43, 0x52, 0x52, 0x01, 0x01, 0x01, 0x02, 0x00, 0x78, 0x01])
    assert rc.encode_button(120, rc.ACTION_DOWN, version=0x01) == expected


def test_pointer_bytes_match_client_le_int16():
    # x=12, y=-4 as little-endian int16.
    expected = bytes([0x45, 0x43, 0x52, 0x52, 0x01, 0x01, 0x02, 0x04, 0x00,
                      0x0C, 0x00, 0xFC, 0xFF])
    assert rc.encode_pointer(12, -4, version=0x01) == expected


def test_frame_length_matches_bArr7_plus_9():
    assert rc.frame_length(rc.encode_button(120, 1)[:9]) == 11  # 2 + 9
    assert rc.frame_length(rc.encode_pointer(0, 0)[:9]) == 13   # 4 + 9


def test_decode_every_keycode_roundtrips():
    for keycode, name in rc.KEYCODES.items():
        for action in (rc.ACTION_DOWN, rc.ACTION_UP):
            ev = rc.decode_frame(rc.encode_button(keycode, action, version=7))
            assert isinstance(ev, rc.ButtonEvent)
            assert ev.keycode == keycode
            assert ev.name == name
            assert ev.action == action
            assert ev.version == 7


def test_decode_pointer_signed():
    ev = rc.decode_frame(rc.encode_pointer(-1000, 2000))
    assert isinstance(ev, rc.PointerEvent)
    assert (ev.x, ev.y) == (-1000, 2000)


def test_parser_handles_multiple_and_split_frames():
    a = rc.encode_button(120, 1)      # MENU down
    b = rc.encode_button(120, 2)      # MENU up
    c = rc.encode_pointer(5, 6)
    parser = rc.RrcFrameParser()
    # two whole frames in one chunk
    evs = parser.feed(a + b)
    assert [str(e) for e in evs] == ["button  MENU        down", "button  MENU        up"]
    # a frame split across two feeds
    assert parser.feed(c[:7]) == []
    evs = parser.feed(c[7:])
    assert len(evs) == 1 and isinstance(evs[0], rc.PointerEvent)


def test_parser_resyncs_past_garbage():
    parser = rc.RrcFrameParser()
    good = rc.encode_button(13, 1)  # OK/ENTER down
    evs = parser.feed(b"\x00\xff garbage " + good)
    assert len(evs) == 1
    assert evs[0].name == "OK/ENTER"


def test_bad_magic_rejected():
    try:
        rc.decode_frame(b"XXXX" + bytes(7))
    except rc.ProtocolError:
        return
    raise AssertionError("expected ProtocolError for bad magic")


# --- opcode 3 (touch) ---------------------------------------------------------------------

def test_touch_frame_matches_documented_layout():
    """15 bytes: opcode 3, payload len 6, action, seq, then LE int16 coords."""
    f = rc.encode_touch(rc.TOUCH_DOWN, 0, 0x1234, 0x5678, version=0x10)
    assert len(f) == 15
    assert f[:4] == rc.MAGIC
    assert f[4] == 0x01
    assert f[5] == 0x10                      # version byte from mDNS ("1.10" -> 0x10)
    assert f[6] == rc.OPCODE_TOUCH == 3
    assert (f[7], f[8]) == (0x06, 0x00)      # payload length 6
    assert (f[9], f[10]) == (rc.TOUCH_DOWN, 0)
    assert f[11:13] == bytes([0x34, 0x12])   # x little-endian
    assert f[13:15] == bytes([0x78, 0x56])   # y little-endian


def test_touch_length_rule_holds():
    assert rc.frame_length(rc.encode_touch(rc.TOUCH_MOVE, 7, 0, 0)[:9]) == 15  # 6 + 9


def test_touch_seq_and_actions_round_trip():
    for action in (rc.TOUCH_DOWN, rc.TOUCH_UP, rc.TOUCH_MOVE):
        f = rc.encode_touch(action, 42, 1, 2)
        assert f[9] == action and f[10] == 42


def test_norm_xy_maps_pixels_to_0_65535():
    # Coordinates are normalised across the video area, NOT MFD pixels.
    assert rc.norm_xy(0, 0, 800, 480) == (0, 0)
    # centre -> 32768, which as a signed int16 is -32768 (the app narrows via Short too)
    assert rc.norm_xy(400, 240, 800, 480) == (-32768, -32768)
    # bottom-right clamps to 0xFFFF -> -1 signed
    assert rc.norm_xy(800, 480, 800, 480) == (-1, -1)
    # out-of-range input is clamped, never wrapped into a wrong quadrant
    assert rc.norm_xy(-50, 9999, 800, 480) == (0, -1)


def test_touch_frames_decode_back_to_pixels():
    """A touch frame must survive encode -> decode -> back to the original MFD pixel."""
    for px, py in [(0, 0), (255, 156), (400, 240), (582, 281), (799, 479)]:
        frame = rc.encode_touch(rc.TOUCH_DOWN, 0, *rc.norm_xy(px, py, 800, 480))
        ev = rc.decode_frame(frame)
        assert isinstance(ev, rc.TouchEvent)
        fx, fy = ev.fraction
        # round-trip through 0..65535 is lossy by well under a pixel
        assert abs(round(fx * 800) - px) <= 1
        assert abs(round(fy * 480) - py) <= 1


def test_touch_action_and_seq_survive_decode():
    ev = rc.decode_frame(rc.encode_touch(rc.TOUCH_MOVE, 54, 100, 200))
    assert (ev.action, ev.action_name, ev.seq) == (rc.TOUCH_MOVE, "move", 54)


def test_unknown_opcode_does_not_break_the_stream():
    """An unmapped opcode must be surfaced, not fatal — the emulator doubles as an opcode probe.

    Regression: decode_frame() raised ProtocolError on opcode 3, and rrc_server's catch-all
    logged it and *dropped the client*, so touch frames silently killed the connection.
    """
    unknown = bytes([0x45, 0x43, 0x52, 0x52, 0x01, 0x10, 0x07, 0x02, 0x00, 0xAA, 0xBB])
    events = rc.RrcFrameParser().feed(unknown + rc.encode_button(27, rc.ACTION_DOWN))
    assert isinstance(events[0], rc.UnknownEvent)
    assert isinstance(events[1], rc.ButtonEvent) and events[1].name == "BACK"


def test_full_gesture_reassembles_from_single_byte_reads():
    """TCP gives no framing guarantees; a drag must survive worst-case fragmentation."""
    blob = rc.encode_touch(rc.TOUCH_DOWN, 0, *rc.norm_xy(182, 125, 800, 480))
    blob += b"".join(rc.encode_touch(rc.TOUCH_MOVE, i, *rc.norm_xy(200 + i, 130 + i, 800, 480))
                     for i in range(1, 55))
    blob += rc.encode_touch(rc.TOUCH_UP, 0, *rc.norm_xy(582, 281, 800, 480))

    parser = rc.RrcFrameParser()
    events = [e for b in blob for e in parser.feed(bytes([b]))]

    actions = [e.action for e in events]
    assert actions[0] == rc.TOUCH_DOWN and actions[-1] == rc.TOUCH_UP
    assert actions.count(rc.TOUCH_MOVE) == 54
    # seq counts MOVEs within the gesture and is 0 on the DOWN/UP bookends
    assert [e.seq for e in events] == [0] + list(range(1, 55)) + [0]
