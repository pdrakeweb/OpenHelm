# OpenHelm

An Android remote control for Wi-Fi marine multifunction displays. It mirrors the chartplotter's
screen on your phone and drives the display from the cockpit.

Works with Raymarine c-Series, e-Series and a-Series Wi-Fi MFDs. The protocol was recovered from a
Raymarine e95 (HybridTouch), and the app is tested against a simulator that speaks it. OpenHelm is
an independent project; Raymarine is a trademark of its owner.

## Video

The display's live picture, letterboxed to its native 5:3 and decoded through `MediaCodec` with
low-latency mode enabled. Frames are released as they arrive and dropped rather than queued when the
decoder falls behind. The target is under 500 ms glass to glass.

If the link drops, the last frame is covered by a scrim and a banner reading VIDEO NOT LIVE, naming
the cause in plain language. The scrim blocks touches as well as light, so a tap cannot reach a
chart position that is minutes old.

## Controls

Home, Menu, Back, Zoom in, Zoom out, Pane and Waypoint, arranged around a rotary dial with a confirm
hub, four direction sectors and an outer ring that steps the chart range. Every control is at least
56dp, for wet or gloved hands. Press and release travel as separate messages, so holding a key
sweeps the cursor using the display's own auto-repeat.

Two modes: **Mirror** puts the picture beside the controls, and **Remote** gives the controls the
whole screen and stops the video pipeline, for when the chart is being read off the display itself.

In Mirror mode, tapping or dragging the picture places and moves the cursor on the display. Pinch
and two-finger drag zoom and pan your own view of the picture and stay local to the phone.

## Light conditions

High contrast, dark and night palettes, one tap apart on the remote screen and listed by name in
Settings. The choice is remembered across launches, and a fresh install starts in dark.

High contrast is built to WCAG's AAA thresholds — 7:1 for body text, 4.5:1 for large text and icons
— and puts every control on the screen as a near-black slab on a white page, because sunlight
compresses contrast from the top down and a light tint of the page has no edge left once the
highlights are gone. Night is red-dominant and dims the video along with the interface, since the
chart is the largest bright object on screen. The ratios are computed and asserted in
`PaletteContrastTest`, so a change that looks better but reads worse fails the build.

## Connecting

Displays are found over mDNS and saved on first connection, where they can be given a name so "E9"
becomes "Helm". Launching tries the saved displays first, then scans. An address can also be typed
in, which is the reliable path on boat Wi-Fi that blocks multicast.

Simulation mode runs the whole interface against a chart scene generated on the device — data bar,
coastline, soundings, a route with a vessel on it, a cursor and a menu column — so the app can be
explored, and the panel and palettes judged against a realistic picture, with no display present. It
lasts for the session only.

## How it works

The display advertises two services over mDNS/DNS-SD, and OpenHelm connects to both independently.

**Video** is a stock RTSP server publishing H.264 (800×480, High@4.0) at `_rtsp._tcp`. RTP is
carried over UDP.

**Control** is a binary TCP protocol at `_rym_rrc._tcp`. Frames open with the magic `ECRR` and a
9-byte header carrying a protocol version and one of three opcodes: button (a Windows virtual
keycode plus press or release), zoom, and absolute touch with coordinates normalised to 0–65535.

Both services carry TXT records giving the RTSP path, the model, the serial and the control protocol
version. The full wire format is in [`docs/protocol.md`](docs/protocol.md).

## Modules

| Module | Contents |
|---|---|
| `protocol/` | Plain JVM Kotlin. Wire format, keycodes, RTP/H.264 depacketisation, SDP and discovery parsing. |
| `app/` | Android app: Compose UI, `NsdManager` discovery, RTSP client, `MediaCodec` video. |

`protocol/` carries no Android dependencies, so the wire format can be tested without a device and
reused by desktop tooling.

## Building

Android Studio, or:

```bash
./gradlew :app:assembleDebug
```

Requires Android 8.0 (API 26) or later. The APK is universal across all four ABIs, with `arm64-v8a`
as the primary target.

```bash
./gradlew :protocol:test :app:testDebugUnitTest
```

covers the wire format, RTP depacketisation, SDP parsing, the adaptive layout arithmetic, the
palette contrast ratios, the failure-text sanitiser and the remembered-display codec. Device
procedures are in [`tests/`](tests/README.md).

Latency is measured on ARM hardware. `MediaCodec` on an x86_64 emulator resolves to a software
decoder, and low-latency decode, codec priority, output-buffer timing and colour-format handling all
behave differently there than on a phone's hardware decoder.

## Safety

OpenHelm mirrors and remote-controls a display. Navigate from your instruments and keep a proper
watch. The software is provided without warranty of any kind.

## Provenance

OpenHelm is a clean-room implementation written from a protocol description: facts about bytes on a
wire, obtained by observing a device the authors own. It contains no vendor code, resources, artwork
or branding. See [CLEAN-ROOM.md](CLEAN-ROOM.md).

## Licence

[Apache License 2.0](LICENSE).
