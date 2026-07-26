# The MFD wire protocol

Everything OpenHelm needs to talk to a Wi-Fi marine multifunction display, stated as facts about
bytes. Obtained by observing a device we own; contains no vendor code. See
[../CLEAN-ROOM.md](../CLEAN-ROOM.md).

Reference device: **Raymarine E9**, firmware as shipped, on its own Wi-Fi access point.

---

## 1. Discovery

Standard mDNS / DNS-SD. Three service types are advertised; two matter.

| Service | Purpose |
|---|---|
| `_rtsp._tcp` | video stream |
| `_rym_rrc._tcp` | remote control |
| `_rym_pt._udp` | camera pan/tilt — not supported by OpenHelm |

TXT keys:

| Key | On `_rtsp._tcp` | Meaning |
|---|---|---|
| `raymarine-mfd-rtsp-path` | ✓ | path component of the stream URL |
| `raymarine-mfd-model` | ✓ | e.g. `E9` |
| `raymarine-mfd-serial` | ✓ | unit serial |
| `raymarine-mfd-rrc-version` | on `_rym_rrc._tcp` | protocol version **string** — see §1.1 |

The stream URL is `rtsp://<A-record address>:<SRV port>/<rtsp-path>`. On the reference device:
`rtsp://192.168.131.1:8554/RAYMARINEMFD`, with control on TCP **50000**.

### 1.1 The version byte — not the obvious parse

`raymarine-mfd-rrc-version` is a string such as `"1.10"`. The value that belongs in a control-frame
header is **characters `[2,4)` parsed as hexadecimal**:

```
"1.10"  ->  "10"  ->  0x10  ->  16
```

Not 1. Not 10. This is peculiar but it is what the device expects, so it must be reproduced
exactly. Anything unparsable falls back to `0x01`. Getting this wrong is silent — frames simply do
not take effect.

## 2. Video

- **Transport:** RTSP, with RTP over **UDP**.
- **Server:** a stock GStreamer RTSP server. No vendor framing, no authentication, no handshake —
  a general-purpose player can open the URL unmodified.
- **SDP:** one video track, `m=video 0 RTP/AVP 96`, `a=rtpmap:96 H264/90000`.
- **Bitstream:** H.264 **High Profile** (`profile_idc` 100), **Level 4.0**, **800×480**, `yuv420p`.

> ⚠️ **Do not request TCP-interleaved RTP.** The reference device accepts the SETUP and then never
> delivers media — an interleaved request stalled indefinitely, where the same request over UDP
> produced a frame in about a second. A client that defaults to TCP interleaving will look like it
> connected and then show nothing, with no error to explain it.

800×480 is 5:3. On a phone in landscape, letterbox it; stretching to fill is visibly wrong.

## 3. Control

TCP, a single connection, `SO_KEEPALIVE` and `TCP_NODELAY`. Each message is written as one frame.
The device does not reply; the socket is read only to notice that it closed.

### 3.1 Framing

```
offset  bytes         meaning
0..3    45 43 52 52   magic ("ECRR" in ASCII)
4       01            constant
5       vv            version byte, from §1.1
6       op            opcode
7..8    ll hh         payload length, little-endian (hh observed always 0)
9..     ....          payload
```

Total frame length is `payload length + 9`.

### 3.2 Opcode `0x01` — key

Payload (2 bytes): `[keycode][action]`, where action is **1 = press**, **2 = release**.

Keycodes are Windows virtual-key values:

| Key | Code | | Key | Code |
|---|---|---|---|---|
| Left | 37 | | Home | 118 |
| Up | 38 | | Menu | 120 |
| Right | 39 | | Back | 27 |
| Down | 40 | | Range out | 33 |
| OK / Enter | 13 | | Range in | 34 |
| | | | Switch pane | 122 |
| | | | Waypoint / MOB | 119 |

**Press and release are separate frames, and the gap between them is meaningful.** The device
implements auto-repeat itself: holding a direction key produces continuous cursor movement, and then
chart panning once the cursor reaches the edge.

Measured on the reference device: 20 discrete taps moved the cursor about 23 px — roughly **1 px per
tap** — while a **2-second hold** drove the cursor to the screen edge and then panned the chart
about 20 nautical miles. So a hold is the only practical way to move the cursor any distance.

A client must therefore send press and release as the user actually produces them, and must never
synthesise an immediate release after a press.

### 3.3 Opcode `0x02` — zoom (**not** a pointer)

Payload (4 bytes): `[x_lo][x_hi][y_lo][y_hi]` — two little-endian signed 16-bit values.

Despite carrying what look like coordinates, **this opcode changes the chart range; it does not move
a cursor.** Verified: sending plausible on-screen positions stepped the chart 5 nm → 4 nm → 2500 ft
while the cursor stayed exactly where it was. Negative values zoom out.

This is the easiest thing in the protocol to model wrongly. Do not call it "pointer".

### 3.4 Opcode `0x03` — touch

Payload (6 bytes): `[action][seq][x_lo][x_hi][y_lo][y_hi]`.

- `action` — **1 = down, 2 = up, 3 = move**
- `seq` — 0 on down and up; increments on each move within one gesture (wraps at 256)
- `x`, `y` — **normalised to 0..65535 across the video area**, little-endian

Coordinates are *not* pixels, which is what makes the protocol resolution-independent:

```
x = (event.x - xOffset) / (viewWidth  * scale) * 65535
y = (event.y - yOffset) / (viewHeight * scale) * 65535
```

Out-of-range points should be dropped or clamped, never wrapped. A tap is `down` + `up` at one
point; a drag is `down` → *n* × `move` → `up`.

> **Support is unconfirmed.** This opcode was found in a companion app for *tablets*, and a client
> emitting it has been verified frame-by-frame against a simulator — but not against real hardware.
> The reference E9 is a **non-touch** display (its own front panel is keys and a rotary), so it may
> ignore opcode 3 entirely, and touch may be a feature of touch-capable models only.
>
> **Always keep the key-based cursor path (§3.2) as a fallback.** To test a device: send a `down`
> and `up` at a known position and see whether the cursor moves there.

## 4. What the device's network does *not* give you

The MFD's Wi-Fi is an **isolated access point** — a scan of the range found only the display and the
connecting client. It is **not bridged to the NMEA 2000 backbone**, so instruments and the autopilot
are **not reachable over IP**, and there is no NMEA traffic to listen to.

Practical consequence: there is no data path. Anything a client wants either comes over the control
channel or has to be read off the video pixels. Reaching the on-screen autopilot controls means
putting the cursor on them — which is why §3.2's hold behaviour and §3.4's touch matter.

**One unexplored lead:** the device also listens on TCP **5672** with an **AMQP 0-10** broker. That
is the most promising remaining candidate for structured data (position, depth, autopilot state).
Unprobed.

## 5. Worked examples

Every byte below is from a verified encoder.

```
key: Menu press, version 0x10
45 43 52 52 01 10 01 02 00 78 01
└── magic ──┘ __ vv op ln __ │  └─ action 1 = press
                                └─── keycode 120 = Menu

key: Back release, version 0x01
45 43 52 52 01 01 01 02 00 1b 02

zoom: x=12, y=-4, version 0x01
45 43 52 52 01 01 02 04 00 0c 00 fc ff
                                └─ -4 as little-endian int16

touch: down at the centre of an 800x480 area, version 0x10
45 43 52 52 01 10 03 06 00 01 00 00 80 00 80
                              │  │  └──┴──┴──┴─ 0x8000, 0x8000 = centre
                              │  └─ seq 0
                              └─ action 1 = down

touch: move, seq 7, at (600,300) of 800x480
45 43 52 52 01 10 03 06 00 03 07 ff bf ff 9f

touch: up at the bottom-right corner
45 43 52 52 01 10 03 06 00 02 00 ff ff ff ff
```
