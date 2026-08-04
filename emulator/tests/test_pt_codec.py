"""PT slew codec conformance against a/i.java (deferred feature; codec still verified)."""

import math

from mfd_emulator import pt_codec as pt


def test_subscribe_bytes_match_client():
    # a.i.a(): {P,T,0,0,1,0,2,0}
    assert pt.encode_subscribe() == bytes([0x50, 0x54, 0x30, 0x30, 0x01, 0x00, 0x02, 0x00])


def test_status_online_bytes():
    # a.i.b() template with the online byte set: {P,T,0,0,1,0,3,1,1}
    assert pt.encode_status(True) == bytes([0x50, 0x54, 0x30, 0x30, 0x01, 0x00, 0x03, 0x01, 0x01])
    assert pt.encode_status(False)[-1] == 0x00


def test_slew_big_endian_scale():
    # 1.0 rad -> int(1e8) = 0x05F5E100, big-endian.
    frame = pt.encode_slew(1.0, 0.0)
    assert frame is not None
    assert frame[8:12] == bytes([0x05, 0xF5, 0xE1, 0x00])


def test_slew_roundtrip():
    for az, el in [(0.0, 0.0), (1.0, 0.5), (math.pi, math.pi / 2), (6.28, 3.14)]:
        frame = pt.encode_slew(az, el)
        assert frame is not None
        msg = pt.decode(frame)
        assert isinstance(msg, pt.SlewCommand)
        assert abs(msg.azimuth_rad - az) < 1e-6
        assert abs(msg.elevation_rad - el) < 1e-6


def test_slew_out_of_range_returns_none():
    assert pt.encode_slew(-0.1, 0.0) is None
    assert pt.encode_slew(7.0, 0.0) is None
    assert pt.encode_slew(0.0, 100.0) is None


def test_decode_subscribe_and_status():
    assert isinstance(pt.decode(pt.encode_subscribe()), pt.SubscribeMessage)
    st = pt.decode(pt.encode_status(True))
    assert isinstance(st, pt.StatusMessage) and st.camera_online is True
