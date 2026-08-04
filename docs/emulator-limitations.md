# MFD emulator — what it can and cannot prove

The emulator ([`../emulator/`](../emulator/)) is good enough to develop against away from the boat,
but it is **not** a substitute for the real MFD, and one popular way of running it — the standard
Android AVD — **cannot test video at all**. This page is the honest list, so nobody concludes
"it works" from a test that could not have failed, or "it's broken" from an artefact of the rig.

Everything below was observed directly, on 2026-07-24, unless marked otherwise.

---

## 1. The big one: a standard AVD cannot receive RTP over UDP

**Do not judge video on a standard Android AVD.** The limitation is in the rig, not the app, and
it is not configurable away.

The emulator's SLIRP NAT does not forward **inbound UDP** for which no outbound mapping exists.
RTSP negotiates a client UDP port, the server sends RTP to it, and on an AVD guest exactly zero
packets arrive. This is the classic RTSP-over-NAT problem and it defeats any client, however
written — it is a property of the network the guest is on.

OpenHelm's own way through this is **RTP interleaved over the RTSP TCP connection**, which
traverses SLIRP normally because it is just TCP. The app falls back to it automatically against a
simulator host, and `mfd.yaml` can offer it explicitly:

```yaml
rtsp:
  transports: ["tcp"]
```

> ⚠ **This is a simulator-only deviation.** A real E9's GStreamer server accepts an interleaved
> SETUP and then never delivers a frame — `ffmpeg -rtsp_transport tcp` hangs for minutes against
> the real unit while UDP grabs a frame in about a second. So TCP interleaving makes the decode
> path testable on an AVD and proves *nothing* about the boat.

> ⚠ **Never measure latency on an AVD**, over any transport. See
> [`../tests/11-latency.md`](../tests/11-latency.md), which treats the AVD as BLOCKED for latency
> work rather than merely inaccurate.

### What the AVD *is* still good for
Launch and crash testing, manual addressing, connection state transitions, layout and lifecycle,
the whole fault-injection suite, and the RRC control channel — all of which are TCP or purely
local. Over interleaved TCP it also covers the decode path itself. That is most of the app.

### What to use instead
A **real phone on real Wi-Fi**, pointed at this emulator: no NAT, so UDP flows and the transport
is the one the boat uses. That is rig B in [`../tests/README.md`](../tests/README.md), and it is
the cheapest rig that can say anything trustworthy about video.

---

## 2. Fidelity gaps vs. a real Raymarine E9

Measured against a live E9 (capture recorded in the parent research repo). Several of these
were corrected in the emulator once the real device was captured; they are listed because *any* of
them can make the emulator pass where the real MFD fails.

| Aspect | Real E9 | Emulator | Risk if they diverge |
|---|---|---|---|
| RTSP port | **8554** | 8555 by default | Cosmetic only — the app uses whatever the SRV record advertises. 8555 exists solely because QEMU squats `127.0.0.1:8554`. |
| RTSP path | `RAYMARINEMFD` | configurable | A client's `RAYMARINEMFD` presence check is satisfied by the *path value* on the real unit, so it ships **no** separate marker TXT key. If you configure a different path the emulator adds a synthetic marker — which the real device never sends. |
| H.264 profile | **High, Level 4.0** | matched (was Baseline) | A decoder that only handles Baseline would have passed against the old emulator and failed on the boat. |
| Frame size | 800x480 | matched | Exactly 5:3, which is what the app letterboxes to; a mismatch would silently distort touch mapping. |
| RTP transport | **UDP** (GStreamer server; TCP-interleaved requests stall forever) | UDP unless overridden | Forcing `transports: ["tcp"]` is a *deliberate deviation* — it tests something the real MFD cannot do. |
| RRC version TXT | `1.10` → frame byte `0x10` | matched (was `0x01`) | The version byte goes into every control frame. |
| RRC port | 50000 | 2560 | Cosmetic — advertised via SRV. |
| Identity | model `E9`, serial `E70021 0620410` (note the space) | matched | Only affects UI text. |

**Not modelled at all:** the MFD's actual behaviour. The emulator replays a canned video loop and
logs control frames; it does not move a cursor, open menus, change range, or respond to input in
any way. Anything about *what the MFD does when you press a button* can only be tested on the boat.

---

## 3. Protocol coverage

| Channel | Status |
|---|---|
| `_rtsp._tcp` discovery | Implemented; TXT records verified against a reimplementation of the client's own parser. |
| `_rym_rrc._tcp` control | Implemented, and **validated byte-for-byte against a real phone** — every keypad key, joystick direction, tap-as-OK and the rotary decode to the expected frames. |
| `_rym_pt._udp` pan/tilt | **Not advertised.** Codec and tests exist (`pt_codec.py`); nothing serves it. |
| Control *semantics* | Absent. We decode and log what the app sends; we never model a response. The meaning of the zoom opcode's two int16s is settled (it changes range, it is not a pointer); what remains unsettled is how the display scales a *touch* coordinate — see `scripts/mfd_ctl.py touch-encoding`. |

---

## 4. Host-side gotchas that look like app bugs

- **Wi-Fi profile must be Private on Windows.** On a Public profile mDNS is blocked outright and
  the app simply never discovers the emulator. Inbound rules are also needed for TCP 2560/8555 and
  UDP 5353 (plus 8000/8001 for RTP).
- **Port 8554 is unusable on an AVD host** — QEMU binds `127.0.0.1:8554` for its own gRPC console
  service and pre-empts the `10.0.2.2` NAT for that exact port. Symptom: instant EOF, and MediaMTX
  logs nothing at all because the connection never reaches it.
- **MediaMTX logs are the primary evidence when playback fails.** They are captured to
  `emulator/.run/mediamtx.log`; the session line names the negotiated transport, which is usually
  the whole answer.
- The emulator binds `0.0.0.0`, so one instance serves the AVD (via `10.0.2.2`) and a real phone
  (via the LAN IP) simultaneously.

---

## 5. Practical rules

1. **Never conclude "video works on the MFD" from an AVD run.** An AVD run over interleaved TCP
   proves the decoder, Surface and layout work — it says nothing about UDP, the real server's
   behaviour, or latency. Those need a real device.
2. **Never conclude "video is broken" from an AVD run either** — check whether any RTP arrived
   first (MediaMTX's session line names the negotiated transport).
3. When something fails, capture **both** sides: app logcat *and* `emulator/.run/mediamtx.log`.
4. Keep the emulator's stream format matched to the real E9. If it drifts back to Baseline or a
   different frame size, a decoder problem can hide here and only appear on the boat.
5. The emulator proves **plumbing** — discovery, addressing, transport, control framing. It cannot
   prove **behaviour**. Behaviour needs the boat.
