# OpenHelm

An open-source Android remote for Wi-Fi marine multifunction displays — view the chartplotter's
screen on your phone and drive it from the cockpit.

Compatible with **Raymarine c-Series, e-Series and a-Series** Wi-Fi MFDs. Verified against an
**e95** (HybridTouch). *Not affiliated with, endorsed by, or connected to Raymarine.* **Not for
Axiom**, which uses a different protocol entirely.

> **Status: early.** The protocol layer is complete and tested, and the control-only app works
> against a simulated display — see [Roadmap](#roadmap). Nothing here is a navigation instrument;
> see [Safety](#safety).

## Why

The vendor's own app for these displays was last updated in 2017, targets a long-obsolete Android,
and its video runs **6–7 seconds behind** the display it is mirroring. On a boat, a chart that is
seven seconds stale is worse than no chart. OpenHelm is a ground-up replacement whose **primary
design goal is latency** — target **under 500 ms**, glass to glass.

## How it works

Two independent channels, both discovered over mDNS/DNS-SD:

- **Video** — the MFD runs a stock RTSP server publishing H.264 (800×480, High@4.0). Plain RTSP:
  no vendor framing, no authentication. *RTP must go over UDP;* TCP interleaving hangs the server.
- **Control** — a small binary TCP protocol carrying key presses, chart zoom, and absolute touch.

## Modules

| Module | Contents |
|---|---|
| `protocol/` | Pure Kotlin, no Android dependencies. Wire format, keycodes, discovery parsing. Fully unit-tested. |
| `app/` | *(phase 1)* Android app — Compose UI, `NsdManager` discovery, `MediaCodec` video. |

Keeping `protocol/` free of Android types means the wire format is verifiable in milliseconds
without an emulator, and is reusable by desktop tooling.

```bash
./gradlew :protocol:test
```

## Target devices — ARM first

**OpenHelm is deployed to ARM phones and tablets.** `arm64-v8a` is the primary target and the
architecture every release must be verified on; `armeabi-v7a` is carried for older hardware. The
x86/x86_64 slices exist only so the app can run on a convenience emulator, and **an x86_64 emulator
run does not count as verification of a release**.

This matters more than the usual "it's all portable Kotlin", for one specific reason: **the video
pipeline is hardware-dependent.** `MediaCodec` on an x86_64 emulator resolves to a software or
emulated decoder, while a real ARM phone uses the vendor's hardware decoder. Low-latency decode
(`KEY_LOW_LATENCY`), realtime codec priority, output-buffer timing, and colour-format handling can
all behave differently between the two — and latency is this project's headline claim. A pipeline
that looks correct under a software decoder can still miss its budget on the hardware that matters.

Practical rules:

- Build a **universal APK** (all four ABIs) — the default here; do not add ABI splits that would
  drop `arm64-v8a`.
- Run the test suite on **real ARM hardware**, or an `arm64-v8a` AVD if your host can actually boot
  one, before calling a change verified. See [tests/README.md](tests/README.md) for the rigs.
- **Latency is only ever measured on real ARM hardware.** No emulator figure is meaningful.

> ⚠️ **On a Windows/x86_64 dev machine there is no local `arm64` AVD, full stop.** Checked directly
> on this project's machine: the current Android emulator (36.5.11 / 36.6.11) refuses outright to
> boot an `arm64-v8a` system image on an x86_64 host — `FATAL: Avd's CPU Architecture 'arm64' is not
> supported by the QEMU2 emulator on x86_64 host. System image must match the host architecture.`
> Older emulator releases had ARM-on-x86 support via software instruction translation, but it is gone
> from the versions this SDK channel offers, and it was reportedly slow enough even when it existed
> to be impractical for a video-heavy app. The x86_64 AVD remains fully useful for everything
> architecture-independent (UI, navigation, protocol framing, persistence); **arm64 and latency
> verification require a real ARM phone or tablet.** See [tests/README.md](tests/README.md) §0.

The app contains **no native code of its own**. The only `.so` files in the APK come from AndroidX
(`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`) and ship for every ABI. This is
the structural improvement over the app OpenHelm replaces, which was pinned to a hand-built pair of
32-bit-only native libraries and died when the platform dropped 32-bit ARM.

## Design commitments

**Latency is a feature.** Video goes RTSP/RTP → `MediaCodec` → Surface with no buffering beyond a
frame interval, output buffers released immediately rather than scheduled against a stream clock,
and frames dropped rather than queued when the decoder falls behind. A stale picture is worse than a
dropped one.

**Manual addressing is first-class.** mDNS is unreliable on real boat Wi-Fi, so typing the MFD's
address is a normal way to connect, not a debug affordance.

**Control works without video.** The two channels are independent, and a remote that still works
when the video path is broken is genuinely useful.

**Hold means hold.** Key press and release are separate messages, because the MFD's own auto-repeat
is what makes coarse cursor movement possible. A synthetic release would break it.

## Roadmap

| Phase | | Status |
|---|---|---|
| 0 | Protocol: framing, keys, zoom, touch, discovery parsing | ✅ done, tested |
| 1 | Control-only app: discovery, manual address, Compose keypad | ✅ built; verified against the simulator, on-water pending |
| 2 | Video: RTSP/RTP + `MediaCodec`, side-by-side layout, latency overlay | ✅ built; ~0 s behind the simulated source (±0.5 s rig), real-display UDP run pending |
| 3 | Touch and gestures: tap/drag to the MFD, pinch/pan locally | |
| 4 | Structured data — the MFD also exposes an AMQP broker (unexplored) | speculative |

## Safety

**This is not a navigation instrument.** It mirrors and remote-controls a display; it does not
replace one. Do not rely on it for navigation, collision avoidance, or any decision where being
wrong matters. Keep a proper watch. The software is provided without warranty of any kind.

## Interoperability and provenance

OpenHelm is a **clean-room** implementation written from a protocol description — facts about bytes
on a wire, obtained by observing a device the authors own. It contains no vendor code, resources or
artwork, and reuses no vendor branding. See [CLEAN-ROOM.md](CLEAN-ROOM.md) for the full rules and
the pre-publication checks.

## Licence

[Apache License 2.0](LICENSE).
