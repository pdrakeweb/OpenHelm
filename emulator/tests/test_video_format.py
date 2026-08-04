"""Video-format fidelity: the emulator must publish the same H.264 bitstream shape as a real MFD.

Ground truth is a live capture of a Raymarine E9 on its own Wi-Fi AP (docs/mfd-live-capture.md):

    RTSP  rtsp://192.168.131.1:8554/RAYMARINEMFD      (Server: GStreamer RTSP server)
    SDP   m=video 0 RTP/AVP 96 / a=rtpmap:96 H264/90000
    SPS   profile_idc=100 (High), level_idc=40 (4.0), 800x480, 4:2:0

Before this was pinned the emulator published **Baseline @ 1280x720**, so a decoder that only
coped with Baseline would have passed against the emulator and then failed on the boat. These
tests exist to stop that regression coming back.
"""

from pathlib import Path

from mfd_emulator.config import Config, RtspConfig, load_config
from mfd_emulator.discovery import build_rtsp_info
from mfd_emulator.events import EventLog
from mfd_emulator.video import VideoSupervisor


def _supervisor(cfg: Config, tmp_path: Path) -> VideoSupervisor:
    sup = VideoSupervisor(cfg, "192.168.1.50", EventLog(), tmp_path)
    sup._ffmpeg = "ffmpeg"  # pretend the binary was found; we only inspect the argv
    return sup


def _flag(argv: list[str], flag: str) -> str | None:
    """Value following ``flag`` in an argv list, or None."""
    return argv[argv.index(flag) + 1] if flag in argv else None


# -- defaults match the real E9 ------------------------------------------------------------


def test_default_encode_params_match_real_mfd():
    r = RtspConfig()
    assert (r.width, r.height) == (800, 480)   # real E9 SPS
    assert r.profile == "high"                 # profile_idc=100 (was "baseline")
    assert r.level == "4.0"                    # level_idc=40
    assert r.pix_fmt == "yuv420p"


# -- the ffmpeg argv actually carries them ---------------------------------------------------


def test_testsrc_cmd_uses_mfd_profile_level_and_size(tmp_path):
    argv = _supervisor(Config(), tmp_path)._ffmpeg_cmd()
    assert _flag(argv, "-profile:v") == "high"
    assert _flag(argv, "-level:v") == "4.0"
    assert _flag(argv, "-pix_fmt") == "yuv420p"
    assert _flag(argv, "-c:v") == "libx264"
    # synthetic source is generated directly at the MFD's frame size
    assert any("testsrc=size=800x480" in a for a in argv)


def test_looped_source_is_scaled_to_mfd_frame_size(tmp_path):
    cfg = Config(rtsp=RtspConfig(source="loop:C:/clips/chart.mp4"))
    argv = _supervisor(cfg, tmp_path)._ffmpeg_cmd()
    assert _flag(argv, "-vf") == "scale=800:480"
    assert "-stream_loop" in argv and _flag(argv, "-i") == "C:/clips/chart.mp4"
    assert _flag(argv, "-profile:v") == "high"


def test_encode_params_are_overridable_from_yaml(tmp_path):
    p = tmp_path / "mfd.yaml"
    p.write_text(
        "rtsp:\n  width: 1280\n  height: 720\n  profile: baseline\n  level: '3.0'\n  fps: 30\n",
        encoding="utf-8",
    )
    cfg = load_config(p)
    assert (cfg.rtsp.width, cfg.rtsp.height, cfg.rtsp.profile) == (1280, 720, "baseline")
    argv = _supervisor(cfg, tmp_path)._ffmpeg_cmd()
    assert _flag(argv, "-profile:v") == "baseline"
    assert _flag(argv, "-level:v") == "3.0"
    assert any("testsrc=size=1280x720:rate=30" in a for a in argv)


def test_publish_target_uses_configured_port_and_path(tmp_path):
    cfg = Config(rtsp=RtspConfig(port=8554, path="RAYMARINEMFD"))
    argv = _supervisor(cfg, tmp_path)._ffmpeg_cmd()
    assert argv[-1] == "rtsp://127.0.0.1:8554/RAYMARINEMFD"


# -- TXT advert shape matches the real device ------------------------------------------------


def test_real_mfd_path_needs_no_synthetic_marker_key():
    """Real E9 has exactly 3 TXT keys; the app's contains("RAYMARINEMFD") gate is satisfied by
    the rtsp-path VALUE, so we must not invent a 4th key when the path already carries it."""
    cfg = Config(rtsp=RtspConfig(path="RAYMARINEMFD"))
    props = build_rtsp_info(cfg, "192.168.1.50").properties
    keys = {k.decode() if isinstance(k, bytes) else k for k in props}
    assert keys == {"raymarine-mfd-rtsp-path", "raymarine-mfd-model", "raymarine-mfd-serial"}


def test_non_raymarine_path_still_gets_the_marker_key():
    """With a custom path the token would be missing, so the marker key must reappear or the app
    would reject the service outright."""
    cfg = Config(rtsp=RtspConfig(path="stream"))
    props = build_rtsp_info(cfg, "192.168.1.50").properties
    keys = {k.decode() if isinstance(k, bytes) else k for k in props}
    assert "RAYMARINEMFD" in keys


# --- latency clock + AVD/HLS path ------------------------------------------------------------

def test_clock_source_burns_in_a_frame_counter(tmp_path):
    cfg = Config(rtsp=RtspConfig(source="clock"))
    argv = _supervisor(cfg, tmp_path)._ffmpeg_cmd()
    vf = _flag(argv, "-vf")
    assert "drawtext" in vf and "%{n}" in vf          # the frame number is the measurement
    assert _flag(argv, "-profile:v") == "high"        # still the MFD's bitstream shape
    assert any("size=800x480" in a for a in argv)


def test_clock_overlay_contains_no_unescaped_colons():
    """Regression: a colon is the filtergraph's own option separator.

    A Windows font path (`C:/Windows/...`) and a `%{pts:hms}` expansion both broke the graph with
    "No option name near ..." -- in every escaping we tried, and identically via argv and shell.
    The fix was to remove colons entirely (drive-relative font path, frame counter not timecode),
    so the invariant worth pinning is simply: no colons in any option *value*.
    """
    import re
    from mfd_emulator.video import _clock_overlay
    vf = _clock_overlay(15)
    # 1. no drive letter in the font path -- "C:/..." and "C\:/..." both failed
    assert not re.search(r"fontfile=[A-Za-z]\\?:", vf), f"drive colon in font path: {vf}"
    # 2. no colon inside a %{...} text expansion (this is what killed %{pts:hms})
    for expansion in re.findall(r"%\{[^}]*\}", vf):
        assert ":" not in expansion, f"colon inside expansion: {expansion}"
    # 3. no backslash escaping left over -- it does not work here and reads as if it does
    assert "\\:" not in vf


def test_mode_1_rtsp_is_the_default():
    """Mode 2 (HLS) is a deliberate deviation from MFD fidelity, so it must never be the default.

    A run that silently used HLS would look like a passing video test while proving nothing about
    the real MFD's transport.
    """
    assert RtspConfig().mode == "rtsp"
    assert RtspConfig().hls is False
    assert RtspConfig(mode="hls").hls is True


def test_unknown_video_mode_is_rejected_loudly():
    """A typo must fail, not fall back -- the two modes differ in what their results prove."""
    import pytest
    from mfd_emulator.config import _video_mode
    assert _video_mode("HLS ") == "hls"          # case/whitespace tolerant
    with pytest.raises(ValueError):
        _video_mode("htls")
