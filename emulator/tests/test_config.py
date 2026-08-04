"""Config loading + the RRC version-byte derivation (mirrors ag.java's value[2:4] hex parse)."""

from mfd_emulator.config import Config, detect_lan_ip, load_config


def test_defaults_when_no_file():
    cfg = load_config(None)
    assert cfg.model == "e125"
    # 8555, not 8554: the AVD's QEMU process binds 127.0.0.1:8554 itself (see RtspConfig).
    # (The real MFD uses 8554; we keep 8555 so the standard AVD rig works — the app takes the
    # port from the SRV record either way.)
    assert cfg.rtsp.port == 8555
    # 50000 + "1.10" are the REAL E9's values (docs/mfd-live-capture.md); "1.10"[2:4] == "10",
    # so the app derives version byte 0x10 — not 0x01 as originally assumed.
    assert cfg.rrc.port == 50000
    assert cfg.rrc_version_byte == 0x10


def test_rrc_version_byte_parsing():
    assert Config(rrc=_rrc("0x01")).rrc_version_byte == 0x01
    assert Config(rrc=_rrc("0x07")).rrc_version_byte == 0x07
    assert Config(rrc=_rrc("0x0A")).rrc_version_byte == 0x0A
    # malformed -> safe default
    assert Config(rrc=_rrc("zz")).rrc_version_byte == 0x01


def test_load_from_yaml(tmp_path):
    p = tmp_path / "mfd.yaml"
    p.write_text(
        "model: c95\nserial: ABC123\nrtsp:\n  port: 9000\n  path: feed\nrrc:\n  port: 2222\n  version: '0x0F'\n",
        encoding="utf-8",
    )
    cfg = load_config(p)
    assert cfg.model == "c95"
    assert cfg.serial == "ABC123"
    assert cfg.rtsp.port == 9000
    assert cfg.rtsp.path == "feed"
    assert cfg.rrc.port == 2222
    assert cfg.rrc_version_byte == 0x0F


def test_detect_lan_ip_returns_ipv4():
    ip = detect_lan_ip()
    parts = ip.split(".")
    assert len(parts) == 4 and all(0 <= int(x) <= 255 for x in parts)


def _rrc(version):
    from mfd_emulator.config import RrcConfig
    return RrcConfig(version=version)
