"""The on-device ``mfd.cfg`` contract, verified from both ends.

A remote reads ``<externalFilesDir>/mfd.cfg`` to find a display it cannot discover over mDNS.
The parse is line-oriented and forgiving in specific ways, so the algorithm is reimplemented in
Python below -- the same pattern ``test_discovery.py`` uses for the mDNS TXT parse -- and
exercised against the exact bytes the emulator emits with ``--emit-config``.

What this pins down:

* the file the emulator generates is parsed by the app into the address the app expects;
* a ``video=`` setting line is never mistaken for the address line (the regression the
  generalised reader exists to prevent — the original reader returned line 1 blindly, so a
  config carrying only ``video=native`` would have been fed to ``manualConnect()`` and thrown);
* the default with no ``video=`` line is the MediaPlayer pipeline.
"""

from __future__ import annotations

from mfd_emulator.config import Config

# -- the client's own parser, reimplemented from the documented contract -----


def app_read_mfd_config_value(contents: str, key: str | None) -> str | None:
    """The client's key lookup over mfd.cfg, reimplemented.

    ``key is None`` -> the first "bare" line (the MFD address line).
    ``key`` given   -> the value of the first ``<key>=<value>`` line, trimmed.
    Blank lines and ``#`` comments are skipped in both modes; ``None`` when nothing matches.
    """
    for raw in contents.split("\n"):
        line = raw.strip()
        if len(line) == 0:          # if-eqz v2, :goto_line
            continue
        if line.startswith("#"):    # if-nez v2, :goto_line
            continue
        if key is not None:         # if-eqz p1, :cond_bare_line
            prefix = key + "="
            if not line.startswith(prefix):
                continue
            return line[len(prefix):].strip()
        if "=" in line:             # contains("=") -> not the address line
            continue
        return line
    return None


def app_resolve_video_mode(contents: str) -> bool:
    """How the ``video=`` key selects a pipeline.

    True = android.media.MediaPlayer, False = the native LIVE555/ffmpeg pipeline. Defaults to
    True; only an explicit case-insensitive ``native`` turns it off.
    """
    value = app_read_mfd_config_value(contents, "video")
    if value is None:
        return True
    return not value.lower() == "native"


# -- the emulator's generated config, parsed by the app's algorithm ----------


def test_emitted_config_without_video_parses_to_the_address():
    cfg = Config()
    contents = cfg.device_config_file("10.0.2.2")
    assert app_read_mfd_config_value(contents, None) == "10.0.2.2:8555:50000:stream:10"
    assert app_resolve_video_mode(contents) is True  # default = MediaPlayer


def test_emitted_config_with_video_native_selects_the_native_pipeline():
    contents = Config().device_config_file("10.0.2.2", "native")
    assert app_read_mfd_config_value(contents, None) == "10.0.2.2:8555:50000:stream:10"
    assert app_resolve_video_mode(contents) is False


def test_emitted_config_with_video_mediaplayer_selects_mediaplayer():
    contents = Config().device_config_file("192.168.4.108", "mediaplayer")
    assert app_read_mfd_config_value(contents, None) == "192.168.4.108:8555:50000:stream:10"
    assert app_resolve_video_mode(contents) is True


def test_generated_header_comment_is_skipped_not_returned_as_the_address():
    contents = Config().device_config_file("10.0.2.2", "native")
    assert contents.splitlines()[0].startswith("#")
    assert not app_read_mfd_config_value(contents, None).startswith("#")


# -- parser edge cases ------------------------------------------------------


def test_a_config_with_only_a_video_line_yields_no_address():
    # The regression guard: the original reader returned line 1 verbatim, so this file would have
    # handed "video=native" to manualConnect(), whose split(":") would then have thrown.
    contents = "video=native\n"
    assert app_read_mfd_config_value(contents, None) is None
    assert app_resolve_video_mode(contents) is False


def test_order_of_lines_does_not_matter():
    contents = "video=native\n10.0.2.2:8555:50000:stream:10\n"
    assert app_read_mfd_config_value(contents, None) == "10.0.2.2:8555:50000:stream:10"
    assert app_resolve_video_mode(contents) is False


def test_blank_lines_and_comments_and_whitespace_are_tolerated():
    contents = "\n  # a comment\n\n   10.0.2.2:8555:50000:stream:10   \n  video =native\nvideo=  mediaplayer  \n"
    assert app_read_mfd_config_value(contents, None) == "10.0.2.2:8555:50000:stream:10"
    # "video =native" does not match the "video=" prefix, so the next line wins.
    assert app_resolve_video_mode(contents) is True


def test_video_value_is_case_insensitive():
    assert app_resolve_video_mode("video=NATIVE\n") is False
    assert app_resolve_video_mode("video=Native\n") is False


def test_unknown_video_value_falls_back_to_mediaplayer():
    assert app_resolve_video_mode("video=exoplayer\n") is True
    assert app_resolve_video_mode("video=\n") is True


def test_missing_or_empty_file_yields_no_address_and_the_default_pipeline():
    assert app_read_mfd_config_value("", None) is None
    assert app_resolve_video_mode("") is True
