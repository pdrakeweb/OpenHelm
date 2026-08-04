"""Discovery TXT records, and -- most importantly -- a replay of the client's EXACT mDNS
parsing algorithm against our advertised records, so we know a real remote will extract the
right values.

The client reads its mDNS library's rendering of the TXT set, which emits each pair as
``\t<key>: <value>\n``, and pulls values out of that string by substring. We reconstruct the
same string and run the same substring parse.
"""

from mfd_emulator.config import Config
from mfd_emulator.discovery import build_rrc_info, build_rtsp_info


def _props(info) -> dict[str, str]:
    dp = getattr(info, "decoded_properties", None)
    if dp is not None:
        return {k: ("" if v is None else v) for k, v in dp.items()}
    out = {}
    for k, v in info.properties.items():
        key = k.decode() if isinstance(k, bytes) else k
        val = "" if v is None else (v.decode() if isinstance(v, bytes) else v)
        out[key] = val
    return out


def _jmdns_tostring(info, ip: str) -> str:
    """Mirror ServiceInfoImpl.toString(): a header then one ``\\t<key>: <value>\\n`` per TXT."""
    sb = [f"[ServiceInfoImpl@1 name: '{info.name}' address: '/{ip}:{info.port}' status: 'x', has data\n"]
    for k, v in _props(info).items():
        sb.append(f"\t{k}: {v}\n")
    sb.append("]")
    return "".join(sb)


# -- the client's own parsers, reimplemented from the protocol description ---


def app_parse_rtsp(s: str, ip: str, port: int) -> tuple[str, str, str, str]:
    assert "RAYMARINEMFD" in s  # as.java gate
    path = s[s.rfind("raymarine-mfd-rtsp-path: ") + 25:]
    path = path[:path.index("\n")]
    model = s[s.rfind("raymarine-mfd-model: ") + 21:]
    model = model[:model.index("\n")]
    serial = s[s.rfind("raymarine-mfd-serial: ") + 22:]
    serial = serial[:serial.index("\n")]
    url = f"rtsp://{ip}:{port}/{path}"
    return url, path, model, serial


def app_parse_rrc_version(s: str) -> int:
    i = s.rfind("raymarine-mfd-rrc-version: ") + 27 + 2  # ag.java offset
    return int(s[i:i + 2], 16)


# -- tests -------------------------------------------------------------------


def test_rtsp_txt_keys_present():
    props = _props(build_rtsp_info(Config(), "192.168.1.50"))
    assert "RAYMARINEMFD" in props
    assert props["raymarine-mfd-rtsp-path"] == "stream"
    assert props["raymarine-mfd-model"] == "e125"
    assert props["raymarine-mfd-serial"] == "0170799"


def test_app_parses_our_rtsp_advert():
    cfg = Config()
    info = build_rtsp_info(cfg, "192.168.1.50")
    s = _jmdns_tostring(info, "192.168.1.50")
    url, path, model, serial = app_parse_rtsp(s, "192.168.1.50", cfg.rtsp.port)
    assert url == "rtsp://192.168.1.50:8555/stream"
    assert (path, model, serial) == ("stream", "e125", "0170799")


def test_app_parses_our_rrc_version():
    cfg = Config()  # default version "1.10" — exactly what a real E9 advertises
    info = build_rrc_info(cfg, "192.168.1.50")
    s = _jmdns_tostring(info, "192.168.1.50")
    # ag.java takes chars [2:4] of "1.10" -> "10" -> 0x10
    assert app_parse_rrc_version(s) == 0x10


def test_app_parses_custom_rrc_version():
    from mfd_emulator.config import RrcConfig
    cfg = Config(rrc=RrcConfig(version="0x0F"))
    s = _jmdns_tostring(build_rrc_info(cfg, "10.0.0.9"), "10.0.0.9")
    assert app_parse_rrc_version(s) == 0x0F
    assert cfg.rrc_version_byte == 0x0F
