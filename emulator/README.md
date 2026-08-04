# MFD Emulator

A process that impersonates a WiFi Raymarine MFD (c/e/a-Series) closely enough that OpenHelm
discovers it, streams video from it, and drives it over the control channel — **with no real
MFD present**. It also fails on demand, which is the other half of what it is for: every
connection and video fault the app has to survive can be injected from here.

It implements the display's side of the same protocol description OpenHelm's client implements,
so the two are independent expressions of one document rather than one derived from the other.

> References below of the form `research/…` are to the private research repository this project
> was extracted from — the interoperability notes, live captures and protocol specification that
> the implementation was written against. They are named rather than linked because they are not
> part of the public tree.

---

## What works today (validated on this host)

- **mDNS/DNS-SD discovery** of `_rtsp._tcp` and `_rym_rrc._tcp` with the exact TXT records the
  app's jmDNS parser expects (verified against the client's own `ServiceInfoImpl.toString()`
  format and its substring parsers — see `tests/test_discovery.py`).
- **RRC control channel** — decodes every button / cursor / rotary frame the app sends into a
  symbolic oracle log (`tests/test_loopback_rrc.py` pushes real client bytes through a socket).
- **RTSP video** via bundled MediaMTX + FFmpeg (graceful "video disabled" if not installed). The
  default source is a generated **Lake Erie chartplotter loop** (`scripts/generate_chartplotter_video.py`)
  — a seamless 30 s animation of a moving vessel over a chart, so "is it rendering?" is answerable
  at a glance instead of squinting at a test pattern. It is encoded **H.264 by our choice**; that
  is a test of the plumbing, not proof of what a real MFD emits (see
  `research/native-libs.md`).
- **MFD finder** — `scripts/find_mfd.py` browses for a real MFD on the boat, prints its TXT records
  and RTSP URL, and emits the `ffprobe` command that identifies the **actual** codec.
- **Fault injection** — discovery timeout, stream starvation, control-socket close, control/video
  endpoints refused, a hung (read-stalled) display, and a whole-network outage. Driven from the
  keyboard menu or scripted over a localhost control port (`scripts/fault.py`).
- 40 passing unit/integration tests; a live in-process smoke test (`scripts/smoke_test.py`).

**Validated against the user's real phone over real Wi-Fi:** the phone discovered this emulator
via mDNS with zero configuration, and the **RRC control protocol was confirmed byte-for-byte** —
every keypad key, every joystick direction, tap-as-OK and the rotary, with real button presses
decoding to the expected frames in the event log.

Still **not** confirmed end-to-end: the app's on-device video *rendering*. It renders black on both
the AVD and the real phone because of a defect in the app's native decoder, not in this emulator —
diagnosis and the MediaPlayer replacement are in
`research/video-pipeline-replacement.md`, and the plan to
verify it is `research/on-device-test-plan.md`.

Pan/tilt "slew" (`_rym_pt._udp`) is **out of scope for v1** (decision Q6); its codec + tests
exist (`mfd_emulator/pt_codec.py`) so the later phase is a drop-in.

---

## Quick start

```powershell
# 1. one-time setup (creates .venv, installs deps)
.\setup.ps1

# 2. (optional) install the video tools — MediaMTX + FFmpeg
.\scripts\install_media_tools.ps1

# 3. run it
.\run.ps1
```

Without video tools, run control-only (discovery + keypad still fully testable):

```powershell
.\run.ps1 --no-video
```

Headless (log only, no keyboard menu — for CI / scripted runs):

```powershell
.\run.ps1 --no-video --no-console --log-file mfd.log
```

The emulator prints a live event log; everything the app sends shows up as a decoded line:

```
12:04:17.882  RRC   button  MENU        down
12:04:17.943  RRC   button  MENU        up
12:04:18.501  RRC   pointer cursor      dx=+12 dy=-4
12:04:19.033  RRC   touch   down  seq=0   norm=(14894,17066) = 22.7%,26.0%  (800x480 -> 182,125)
12:04:19.062  RRC   touch   move  seq=1   norm=(15385,17386) = 23.5%,26.5%  (800x480 -> 188,127)
12:04:19.945  RRC   touch   up    seq=0   norm=(47661,38399) = 72.7%,58.6%  (800x480 -> 582,281)
```

An opcode we have not mapped is reported rather than dropping the client, so the emulator doubles
as a probe for undiscovered messages:

```
12:04:20.115  RRC   ?????   unknown opcode: 7 raw=45 43 52 52 01 10 07 02 00 aa bb
```

## Video modes — pick the right one

**Two modes. Using the wrong one will waste a session.**

```powershell
.\run.ps1                            # MODE 1 "rtsp" (default)
.\run.ps1 --video-mode hls           # MODE 2
```

| | Mode 1 `rtsp` (default) | Mode 2 `hls` |
|---|---|---|
| Serves | RTP over **UDP**, as the real MFD does | additionally **HLS** over HTTP/TCP |
| MFD-faithful | **yes** — the boat's transport | **no.** No MFD serves HLS |
| Standard AVD | ⛔ **cannot work — do not try** | ✅ renders |
| Real phone | ✅ | ✅ |
| Latency-safe | ✅ | ⛔ ~8.5 s behind *by construction* |

**Mode 1 on an AVD cannot work**, and fails confusingly: SLIRP NAT drops inbound UDP, so no RTP
arrives, and AOSP's RTP stack then divides by zero and takes down Android's own mediaserver. Forcing
TCP does not help either. Use a real phone. Full detail:
[`../docs/emulator-limitations.md`](../docs/emulator-limitations.md) §1.

**Mode 2 needs `video_url=` in the device's `mfd.cfg`** to point the app at the HLS URL:

```
10.0.2.2:8555:50000:RAYMARINEMFD:10
video=mediaplayer
video_url=http://10.0.2.2:8888/RAYMARINEMFD/index.m3u8
```

> ⚠️ **Never measure latency in mode 2.** HLS runs seconds behind live no matter how the segments
> are tuned — the same 6–10 s range as the real latency bug, so it will "confirm" it for entirely
> the wrong reason.

## Measuring latency (`--source clock`)

```powershell
.\run.ps1 --source clock
```

Burns a large frame counter into every frame. Point the app **and** a low-latency reference at the
same stream, and subtract the two numbers — both are showing the same frames, so no clock sync is
needed:

```powershell
ffplay -rtsp_transport udp -fflags nobuffer -flags low_delay -framedrop rtsp://<ip>:8555/RAYMARINEMFD
```

`latency = (reference_frame − app_frame) / fps`, with fps 15.

[`scripts/measure_avd_latency.py`](scripts/measure_avd_latency.py) automates the device side: it
derives the source frame from this emulator's own log, so it needs no second client at all.

The reference path itself measures **under ~0.5 s**, so the emulator is not the limit. See
`research/video-latency.md`.

---

## Reaching the emulator from the app

The emulator binds `0.0.0.0`, so an AVD reaches it at the emulator alias `10.0.2.2` with no
`adb reverse` needed. The catch is discovery: **the standard AVD's NAT does not carry mDNS
multicast**, so the app cannot *find* us. Give it the address instead.

```powershell
.
un.ps1 --no-discovery              # servers up, no mDNS
```

Then in OpenHelm: overflow menu → **Manual connect**, and enter the address the emulator prints
at startup:

```
10.0.2.2:8555:50000:RAYMARINEMFD:10
```

`host:rtsp-port:rrc-port:rtsp-path:version-byte`. Success is visible from this side: watch the log
for the RRC `client connected` line and the RTSP session. What this does **not** exercise is the
app's real mDNS discovery — you are injecting the endpoint. For that, see below.

> **Why 8555 and not 8554:** the Android Emulator's own QEMU process binds `127.0.0.1:8554` for
> its internal gRPC console service, which pre-empts the AVD's `10.0.2.2` → host NAT for that
> exact port — guest connections hit QEMU's binding and get an instant EOF, never reaching us.
> Confirmed with `netstat -ano` (the PID on `127.0.0.1:8554` was `qemu-system-x86_64.exe`). 8555
> avoids it. A real MFD serves RTSP on 8554; set `port: 8554` in `mfd.yaml` when that matters.

### Testing real discovery

mDNS needs the app on the **same L2 broadcast domain** as this host, which the stock AVD's NAT
does not bridge. Either run the app on a real phone on the same WiFi as this machine — the
simplest option, and the one that also gets you real UDP RTP — or use a bridged-network Android
VM. Leave `--no-discovery` off and the emulator advertises both services; the app should find it
without being told anything.

> **UDP is not optional on the AVD.** The standard AVD's SLIRP NAT drops inbound UDP, so RTP over
> UDP never arrives. The emulator offers TCP-interleaved RTP for that case and OpenHelm will fall
> back to it against a simulator host — but a real MFD's GStreamer server stalls on interleaved
> RTP, so that path is simulator-only and must never be used to judge latency.

---

## Acceptance checklist (maps to the plan's phases)

| Phase | Check | Pass when |
|-------|-------|-----------|
| 0 | Networking spike | App (bridged VM) resolves `_rym_rrc._tcp` and opens the control socket; first RRCE bytes appear in the host log |
| 1 | Discovery + control | Every keypad button + joystick/rotary gesture logs the correct decoded line; closing the socket (menu `c`) ends the app's remote screen |
| 2 | RTSP video | App goes `DISCOVERY→CONNECTING→STREAMING` and renders video; menu `d` (drop stream) drives `STREAMING_ENDED` + retry |
| — | Discovery timeout | Menu `r` (stop advertising `_rtsp._tcp`) → app hits its 25 s timeout → connection-failed dialog |

Independent of the app, confirm the video path with VLC:
`vlc rtsp://<LAN-IP>:8555/stream`.

---

## Finding a **real** MFD (`scripts/find_mfd.py`)

For the boat. Join the laptop to the MFD's Wi-Fi and run:

```bash
python scripts/find_mfd.py --timeout 20
python scripts/find_mfd.py --timeout 20 --interface 192.168.1.23   # pin the adapter
python scripts/find_mfd.py --json
```

It browses `_rtsp._tcp`, `_rym_rrc._tcp` and `_rym_pt._udp`, prints every TXT key/value verbatim,
assembles the `rtsp://<ip>:<port>/<raymarine-mfd-rtsp-path>` URL exactly as the app's `as.java`
does, and prints ready-to-paste `vlc` / `ffprobe` / `ffmpeg` commands.

**The `ffprobe` command is the point.** We do not know which codec a real MFD emits — the stock
`libstreamerr.so` negotiated it from the SDP and supported five formats, and the only third-party
write-up of this protocol never names one. `ffprobe -show_streams` settles it. See
`research/native-libs.md`.

**Which machine should this run on?** The blog post that documented the protocol doesn't
state the author's platform, but they were doing packet analysis, so almost certainly a **laptop**.
A laptop is the right choice regardless: you must be on the MFD's Wi-Fi to play the stream, and
`ffprobe` — the thing that identifies the codec — lives there, not on the phone.

Exit codes: `0` found, `1` nothing found, `2` found Raymarine services but no `_rtsp._tcp` (on a
real MFD that usually means video sharing is switched off in the MFD's own settings).

---

## Configuration (`mfd.yaml`)

| Key | Default | Effect |
|-----|---------|--------|
| `interface` | auto-detect | LAN IPv4 advertised to the guest |
| `model` / `serial` | `e125` / `0170799` | `raymarine-mfd-model` / `-serial` TXT (shown in app UI) |
| `rtsp.port` / `rtsp.path` | `8555` / `stream` | App plays `rtsp://<ip>:<port>/<path>` |
| `rtsp.source` | `testsrc` | `testsrc` synthetic pattern, `clock` (burned-in frame counter, for latency measurement), or `loop:C:/path/to/capture.h264` |
| `rtsp.mode` | `rtsp` | **Video mode.** `rtsp` = MFD-faithful UDP (default); `hls` = also serve HLS so an AVD can render. See above — an unknown value is rejected, not silently defaulted |
| `rtsp.hls_port` | `8888` | HLS HTTP port (mode 2 only) |
| `rtsp.hls_segment_count` / `.hls_segment_duration` | `3` / `1s` | HLS playlist geometry. Tunable mainly to separate segmenting latency from player latency |
| `rtsp.width` / `.height` | `800` / `480` | Frame size — **the real MFD's** |
| `rtsp.profile` / `.level` | `high` / `4.0` | H.264 profile/level — **the real MFD's** |
| `rtsp.fps` / `.gop` / `.bitrate` / `.pix_fmt` | `15` / `30` / `2M` / `yuv420p` | Encoder tuning |
| `rtsp.transports` | unset (offer UDP) | Restrict what the server offers, e.g. `["tcp"]` |
| `rrc.port` | `2560` | Control-channel TCP port |
| `rrc.version` | `0x01` | App reads chars `[2:4]` as hex → RRC frame `byte[5]`. Set e.g. `0x07` to test version mismatch |

Override the interface at launch: `.\run.ps1 --interface 192.168.4.108`.

> The shipped `mfd.yaml` overrides several of these to the **real E9's** values (see below).

---

## Video-format fidelity (matched to a real MFD)

The stream is encoded to the same bitstream shape as a **live Raymarine E9**, captured on its own
Wi-Fi AP — see `research/mfd-live-capture.md` (raw probe:
`research/assets/mfd_report.txt`).

| Property | Real E9 | Emulator | |
|---|---|---|---|
| RTSP URL shape | `rtsp://<ip>:8554/RAYMARINEMFD` | `rtsp://<ip>:8555/RAYMARINEMFD` | port differs by design¹ |
| Media line | `m=video 0 RTP/AVP 96` | `m=video 0 RTP/AVP 96` | ✅ |
| rtpmap | `H264/90000` | `H264/90000` | ✅ |
| Profile | High (`profile_idc=100`) | High | ✅ (was Baseline) |
| Level | 4.0 (`level_idc=40`) | 4.0 | ✅ (was 3.0) |
| Resolution | 800×480 | 800×480 | ✅ (was 1280×720) |
| Chroma | yuv420p | yuv420p | ✅ |
| RTP transport | UDP (TCP-interleave **hangs** on the real unit) | UDP offered by default | ✅ |
| TXT keys (`_rtsp._tcp`) | path/model/serial only | same (marker key auto-omitted²) | ✅ |

¹ The real MFD uses 8554; we default to 8555 because the Android Emulator's QEMU process binds
`127.0.0.1:8554` itself. On a **bridged** rig (Genymotion) set `port: 8554` for full fidelity — the
app doesn't care either way, it uses the port from the SRV record.
² The real MFD has no separate `RAYMARINEMFD` TXT key: the app's case-sensitive
`contains("RAYMARINEMFD")` gate is satisfied by the *value* of `raymarine-mfd-rtsp-path`. The
emulator only injects a synthetic marker key when you configure a path that lacks the token.

**Known (harmless) deviations** — session-level cosmetics from MediaMTX vs the MFD's GStreamer
server, which no client parses for playback: `s=` session name (`No Name` vs
`Session streamed with GStreamer`), the absent `a=tool:GStreamer` / `a=type:broadcast` /
`a=range:npt=now-` attributes, `a=control:trackID=0` vs `stream=0`, and the `Server:` header.

Verify at any time by DESCRIBE-ing the running emulator and decoding the SPS — the checked-in test
[`tests/test_video_format.py`](tests/test_video_format.py) locks the encode parameters so a
regression back to Baseline/720p fails CI.

---

## Fault injection

Two ways in: the interactive keyboard menu, and a scriptable TCP control port for automated runs.

| Key | `scripts/fault.py` | Action | Failure shape it reproduces |
|-----|--------------------|--------|------------------------------|
| `r` | `discovery-toggle` | toggle `_rtsp._tcp` advertising | discovery timeout / recovery |
| `d` | `stream-drop` | kill FFmpeg, RTSP endpoint stays up | the session **starves** — server answers, no media arrives |
| `s` | `stream-resume` | restart FFmpeg | stream returns |
| `c` | `close-rrc` | close control socket(s), listener stays up | **transient** control drop; the next reconnect succeeds |
| `k` | `rrc-down` / `rrc-up` | stop/resume the RRC listener | the display **left the network**: every reconnect refused |
| `t` | `stall-on` / `stall-off` | stop reading without closing | **hung display**: socket ESTABLISHED, frames ignored |
| `v` | `video-down` / `video-up` | stop/start the whole RTSP endpoint | video refused while control still works |
| `n` / `u` | `net-down` / `net-up` | everything at once | the display **vanishes**, then returns |
| `i` | `status` | status | — |
| — | `log [n]` | tail the event log | — |
| `q` | — | quit | — |

The control port is `127.0.0.1:8571` by default (`--control-port 0` disables it). Localhost-only
on purpose: it is the test rig's kill switch, not part of the simulated MFD.

```bash
python -m mfd_emulator --no-console --log-file .run/events.log   # headless, control port up
python scripts/fault.py net-down
python scripts/fault.py log 20
```

`net-down` is the one to reach for first: a real outage takes advertising, control and video at the
same moment, and testing the three separately never exercises the combined teardown. See
[`../tests/16-resilience-faults.md`](../tests/16-resilience-faults.md) for the
scenarios these were built for and the results of the 2026-07-28 run against OpenHelm.

> **Adding a fault that closes a server?** From Python 3.12 `asyncio.Server.wait_closed()` waits for
> every *connection handler* to finish, and the app's control handler is parked in `read()` by
> design (the MFD never speaks). Awaiting it before dropping the clients hangs the fault — and,
> because the console dispatches inline, the command that fired it. Drop clients first and bound the
> wait, as `RrcServer.stop_listening` does.

---

## Testing

```powershell
.\.venv\Scripts\python.exe -m pytest -q       # 40 unit + integration tests
.\.venv\Scripts\python.exe scripts\smoke_test.py   # live in-process discovery + control
```

`tests/test_discovery.py` is the highest-value test: it reconstructs the client's jmDNS
`toString()` and runs the app's *exact* substring parsers (`as.java` / `ag.java`) against our
advertised records, proving the real app will extract `rtsp://…/stream`, model, serial, and
RRC version `0x01`.

---

## File map

| Path | Role |
|------|------|
| `mfd_emulator/rrc_codec.py` | RRC `ECRR` frame codec + keycode table |
| `mfd_emulator/pt_codec.py` | Pan/tilt `PT00` codec (deferred feature; codec only) |
| `mfd_emulator/discovery.py` | `AsyncZeroconf` advertising of the two services |
| `mfd_emulator/rrc_server.py` | asyncio TCP control server + decode-to-oracle |
| `mfd_emulator/video.py` | MediaMTX + FFmpeg supervisor |
| `mfd_emulator/events.py` | timestamped oracle log |
| `mfd_emulator/faults.py` / `console.py` | fault injection + keyboard menu |
| `mfd_emulator/config.py` / `__main__.py` | config loader + entrypoint |
| `tests/` | codec conformance, config, loopback, discovery-parse |
| `scripts/` | `find_mfd.py` (real-MFD finder), `smoke_test.py`, `install_media_tools.ps1`, `push_device_config.ps1`, `generate_chartplotter_video.py` |

---

## Troubleshooting

- **App crashes immediately on launch (Genymotion):** missing ARM translation for the arm64
  native libs — install the translation package (setup step 4) and reboot the device.
- **App never discovers the MFD:** (a) device not on Bridged networking / wrong subnet; (b) app's
  jmDNS bound to `0.0.0.0` (apply the bind patch); (c) Windows Firewall blocking inbound
  UDP:5353 / TCP:2560 / TCP:8555 (and UDP:8000/8001 for RTP) — allow the Python interpreter
  through the firewall, and make sure the Wi-Fi network's Windows profile is **Private** (mDNS is
  blocked outright on a Public profile).
- **`video DISABLED — missing mediamtx, ffmpeg`:** run `scripts\install_media_tools.ps1`.
- **Stream won't decode in the app but VLC plays it:** the vintage LIVE555/FFmpeg decoder is
  picky — the FFmpeg output is already constrained to H.264 Baseline / yuv420p / frequent
  keyframes; if needed, lower `testsrc` resolution/rate in `video.py`.
