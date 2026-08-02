# OpenHelm performance review — 2026-07-28

**Scope:** the latency budget and runtime cost of the shipping app — video pipeline, Compose
recomposition, allocation in hot paths, main-thread work, startup, memory. Companion to the
[code review](code_review_2026-07-28.md) of the same date, which covered correctness.

**Rig:** A — `OpenHelmPhone35` (x86_64, API 36) + the MFD emulator. Measurements were taken on
both the **release** build (R8, baseline profile) and the **debug** build (which is the only one
whose TCP-interleaved transport can deliver video on an AVD).

> ⚠️ **Glass-to-glass latency is BLOCKED on this rig and is not reported here.** The AVD's only
> working video path is TCP-interleaved through SLIRP, which is neither the transport nor the
> timing a display produces, and its decoder is software. The repo's own rule stands: never judge
> latency on an AVD. What *is* measurable here — queue depth, recomposition scopes, allocation
> rates, main-thread work — is architecture-independent, and that is what this review is built on.

---

## 1. Executive summary

**The core latency design is working and is measurably doing its job.** Queue depth held at **0
for every sample across 40 s of continuous streaming**, which is the single number this
architecture exists to produce: no standing buffer between socket and glass. Frame delivery was
even (50th percentile 17 ms, 99th 48 ms) with **0 missed vsyncs**, no main-thread I/O anywhere,
and no `runBlocking` or `Thread.sleep` in the codebase.

The findings are about the *edges*: what happens when the pipeline hiccups, and work being done
per-frame that does not need to be.

**Top 3 concerns:**

1. **A momentary queue overflow costs up to a full GOP of frozen video** ([P1](#p1)). `MAX_PENDING`
   is 2; exceeding it arms an IDR wait, and every frame until the next keyframe is discarded. At
   the emulator's GOP that is **2 seconds of frozen chart** — the exact hazard the app is built
   around — paid to avoid ~130 ms of latency.
2. **Three composables recompose far more often than their content changes** ([P2](#p2)–[P4](#p4)):
   the video pane once a second in debug builds, and the simulated pane at *frame rate* — 15×/s
   during playback and every frame during a touch gesture.
3. **The RTP receive path allocates per packet** ([P5](#p5)) — roughly 450 short-lived arrays per
   second, ~600 KB/s of garbage, on the one path where a GC pause is visible as a dropped frame.

---

## 2. Measurements

All figures from the AVD; treat absolute values as rig-bound and the *shapes* as meaningful.

### Video pipeline (debug build, TCP transport, 8 samples over 40 s)

| Metric | Observed | Reading |
|---|---|---|
| Queue depth | **0 on every sample** | The design's headline claim, holding. Nothing accumulates. |
| Frame rate | 15–19 fps against a 15 fps source | Tracking the source; brief >15 readings are catch-up after a drop, consistent with render-on-arrival. |
| Decode EMA | 6–29 ms | Software decode. Well inside the 66 ms frame budget — so overflow is *not* decode-bound here. |
| Discontinuities (`gap`) | **0** | No packet loss across the whole run. |
| Dropped AUs | 29 → 52, in bursts of 7–9 | **The finding.** With `gap 0`, these are not loss — they are local overflow recovery. See [P1](#p1). |

### Rendering (release build, 40 s window, counters reset first)

| Metric | Streaming | Idle |
|---|---|---|
| 50th / 90th / 95th / 99th percentile | 17 / 18 / 19 / 48 ms | 22 / 30 / 450 / 450 ms |
| Missed vsyncs | **0** | 4 |
| Slow UI thread | — | 7 |

The idle run's 450 ms tail is cold-start composition (gfxinfo accumulates from process start); the
streaming run was measured after a reset and is the honest steady state. The "88.9 % janky" headline
gfxinfo prints is an artifact of a software renderer sitting a hair over the 16.7 ms vsync — the
distribution (17→19 ms across the 50th–95th) is tight, and 0 missed vsyncs is the signal that matters.

### Startup, size, memory (release build, R8 + baseline profile)

| Metric | Value |
|---|---|
| Cold start (3 runs) | 1233 / 1019 / 862 ms `TotalTime` |
| APK size | **1.9 MB** release vs 13.2 MB debug — R8 shrinks ~86 % |
| Memory idle | Java heap 5.3 MB · native 10.2 MB |
| Memory streaming | Java heap 12.0 MB · native 13.2 MB · **TOTAL PSS 85 MB** |
| Threads | 37–38, stable |

Startup is respectable for a Compose + Hilt app on an emulated x86_64 CPU, and the baseline profile
is being generated (`app/build/outputs/apk/release/baselineProfiles/`). Real ARM hardware should be
materially faster; this number is a ceiling, not a target.

---

## 3. Findings

<a name="p1"></a>
### P1 — A momentary overflow discards up to a full GOP of video (High)

- **Where:** [H264Decoder.kt:121-128](../../app/src/main/kotlin/dev/openhelm/app/video/H264Decoder.kt)
  (overflow → `awaitIdr = true`), [H264Decoder.kt:143-149](../../app/src/main/kotlin/dev/openhelm/app/video/H264Decoder.kt)
  (discard until IDR), `MAX_PENDING = 2` at line 188.

When more than 2 access units are waiting, the queue is cleared to the newest and `awaitIdr` is
armed — after which **every non-IDR access unit is dropped until the next keyframe**. At the
emulator's GOP of 30 frames at 15 fps that is a **2-second window**. The user sees a frozen chart
for up to two seconds, which is precisely the condition the stale-video overlay exists to warn
about — except here the pipeline is healthy and the app will not raise that overlay, because
`VideoState` is still `Streaming`.

The measurements show this firing roughly every 10–15 s on this rig (`drop` climbing 29→52 in
bursts of 7–9 while `gap` stayed 0). Decode time (6–29 ms) is nowhere near the 66 ms budget, so
this is **not** the decoder failing to keep up — it is bursty arrival through the emulator's
TCP-interleaved NAT completing several AUs almost simultaneously and momentarily exceeding a
queue cap of 2.

**That caveat matters: the frequency here is probably a rig artifact.** Real UDP delivery with a
hardware decoder may never trip it. But the *cost when tripped* is real on any transport, and the
trade as written is lopsided: raising `MAX_PENDING` from 2 to 3–4 costs one or two extra frame
intervals of worst-case latency (66–133 ms) to avoid a 2-second freeze.

**Recommendation:** measure `drop`-vs-`gap` on rig B/C first — if overflows occur on real hardware
at all, raise `MAX_PENDING` to 3. Do **not** simply skip the IDR wait: decoding P-frames whose
references were dropped produces a corrupt chart, which is worse than a frozen one. Consider also
surfacing a sustained IDR-wait as the stale-video overlay, since a 2 s freeze is a stale picture by
any definition the user cares about.

<a name="p2"></a>
### P2 — The video pane recomposes once a second in debug builds (Medium)

- **Where:** [VideoPane.kt:67](../../app/src/main/kotlin/dev/openhelm/app/ui/VideoPane.kt) collects
  `videoStats`; the only reads are at lines 172–178, inside `if (BuildConfig.DEBUG)`.

Reading a 1 Hz flow at the top of `VideoPane` makes the **entire pane** — letterbox math, the
`when (videoState)` branch, the gesture `Box` — recompose every second while streaming. In release
R8 strips the reads so no recomposition scope depends on it, but the flow is still *collected*: a
coroutine and a `StateFlow` write per second producing nothing.

**Fix:** move the overlay into its own composable that collects `videoStats` itself, so the
subscription and the recomposition are both scoped to the 20-odd characters that actually change.
Gate the collection on `BuildConfig.DEBUG` so release does no work at all.

<a name="p3"></a>
### P3 — The simulated pane recomposes at 15 fps because the badge prints the frame number (Medium)

- **Where:** [SimulatedRemoteScreen.kt:246](../../app/src/main/kotlin/dev/openhelm/app/ui/SimulatedRemoteScreen.kt)
  — `"not a real display · frame $frame"`.

The same `frame` state is read correctly at line 190 (`drawSimulatedChart(measurer, frame)` inside
the `Canvas` lambda — a **draw-phase** read, which invalidates only drawing) and incorrectly at
line 246, where interpolating it into a `Text` makes it a **composition-phase** read. That forces
`SimulatedVideoPane` and its Column to recompose 15 times a second.

Simulation-only, so it costs nothing on the water — but it is worth fixing because the file
already demonstrates the right pattern eight lines above the wrong one, and because simulation
exists to preview what ships.

**Fix:** hoist the badge into its own composable, or drop the counter from the badge (the drifting
chart already proves the feed is live).

<a name="p4"></a>
### P4 — `rememberTouchMarkClock` recomposes its caller every frame during a gesture (Medium)

- **Where:** [TouchMarks.kt:96](../../app/src/main/kotlin/dev/openhelm/app/ui/TouchMarks.kt)
  returns `clock`; [SimulatedRemoteScreen.kt:155,230](../../app/src/main/kotlin/dev/openhelm/app/ui/SimulatedRemoteScreen.kt)
  assigns it and uses it only inside a `Canvas` lambda.

Returning the `Long` makes it a composition read inside the calling composable, so every frame of
the fade animation recomposes `SimulatedVideoPane` — even though the value's only consumer is a
draw lambda. The recomposition is pure waste.

**Fix:** return `State<Long>` and read `.value` inside the draw lambda, so the clock invalidates
drawing without touching composition.

<a name="p5"></a>
### P5 — The RTP receive path allocates per packet (Medium)

- **Where:** [RtpH264.kt:50](../../protocol/src/main/kotlin/dev/openhelm/protocol/video/RtpH264.kt)
  (`datagram.copyOfRange` per packet), [:110](../../protocol/src/main/kotlin/dev/openhelm/protocol/video/RtpH264.kt)
  and [:118](../../protocol/src/main/kotlin/dev/openhelm/protocol/video/RtpH264.kt)
  (`START_CODE + p` per NAL), [:159](../../protocol/src/main/kotlin/dev/openhelm/protocol/video/RtpH264.kt)
  (final AU array).

At 15 fps with ~30 packets per frame that is ~450 array allocations per second plus a `RtpPacket`
per packet — on the order of 600 KB/s of short-lived garbage, sustained for the whole session.
Modern generational GC handles this without drama, but this is the one path in the app where a
collection pause shows up as a visibly dropped frame, and it scales linearly with resolution: a
future 1080p unit would quadruple it.

**Fix (in cost order):** pass `(array, offset, length)` into the depacketizer instead of copying
the payload out of the datagram; reuse a scratch receive buffer; assemble the access unit into a
reusable growable buffer. The FU-A path already moved to `ByteArrayOutputStream` for its own
(quadratic) reasons on 2026-07-28 — the same treatment applies here.

### P6 — Minor items (Low)

| # | Where | Note |
|---|---|---|
| L1 | [H264Decoder.kt:38](../../app/src/main/kotlin/dev/openhelm/app/video/H264Decoder.kt) | `ArrayDeque<Long>` for submit timestamps boxes every entry. 15/s — negligible, noted only so it is not mistaken for a hot path later. |
| L2 | [H264Decoder.kt:162](../../app/src/main/kotlin/dev/openhelm/app/video/H264Decoder.kt) | `publishStats()` allocates a `Stats` object on every rendered frame **and** inside `feed()`'s loop. Cheap, but it is per-frame allocation in the decode callback; publishing on the 1 Hz stats tick instead would remove it entirely. |
| L3 | Release build | `baselineProfiles/` is produced but there is no Macrobenchmark module generating a *measured* profile — the current one is the AGP default. A real startup profile is the single cheapest startup win available. |

---

## 4. What is done well

1. **The queue cap is not a slogan — it is observable.** `q0` on every sample across 40 s is the
   architecture proving itself; the whole point of owning this pipeline instead of using a stock
   player is that no component is allowed to buffer, and the instrumentation makes that falsifiable
   rather than asserted.
2. **The debug overlay is the right instrument.** `fps · q · dec ms · drop · gap` is precisely the
   five numbers needed to tell "the network is dropping frames" from "the decoder is behind" from
   "the app is buffering" — this review's main finding came directly from `drop` climbing while
   `gap` stayed 0, which no generic profiler would have surfaced.
3. **Draw-phase state reads in the dial.** [Dial.kt](../../app/src/main/kotlin/dev/openhelm/app/ui/Dial.kt)
   reads `frameTick`, `pressed` and `markerTick` inside the `Canvas` lambda, so a 60 fps animation
   invalidates drawing only and never recomposes. This is the pattern P3 and P4 should follow — the
   codebase already knows how, in its most animation-heavy component.
4. **No main-thread I/O.** Every socket runs on `Dispatchers.IO`, `MediaCodec` on its own
   `HandlerThread`, and there is not one `runBlocking` or `Thread.sleep` in the app or protocol
   module.
5. **R8 pays for itself** — 13.2 MB → 1.9 MB, verified working end-to-end on device (Hilt
   injection, DataStore, Compose, navigation, control channel) rather than assumed from a
   successful build.
6. **Thread count is stable at 37–38** across connect/stream/disconnect cycles, and memory grew
   only ~7 MB Java heap between idle and streaming — no evidence of the per-reconnect leak that
   the resilience soak (`tests/20`) is designed to catch.

---

## 5. Recommended next steps

1. **Measure `drop` vs `gap` on rig B or C** (real phone, real UDP, hardware decoder) before
   changing `MAX_PENDING`. If overflows never occur there, P1 is a rig artifact and needs no code
   change; if they do, raise the cap to 3 rather than accept 2-second freezes.
2. **Fix P2–P4 together** — they are one theme (state read in composition where a draw-phase read
   would do) and total maybe 30 lines. P2 also removes pointless work from release builds.
3. **Then P5**, the allocation path, guided by a real profile rather than by this static reading —
   it is the highest-effort item here and the one most likely to be premature.
4. **Add a Macrobenchmark module** for startup and frame timing (L3). It would turn the numbers in
   §2 from a one-off into a regression gate, and generate a real baseline profile as a side effect.
5. **Establish the latency number on hardware.** Everything above is about *not wasting* the
   latency budget; nobody has yet measured what the budget actually is on an ARM phone over UDP.
   [`tests/11-latency.md`](../../tests/11-latency.md) describes the rig — the `clock` video source
   burns a frame counter into every frame for exactly this, and it remains the single most
   important unmeasured number in the project.
