# 07 — The video pipeline

RTSP/RTP negotiated in Kotlin, fed straight into `MediaCodec`, rendered to a `TextureView`, with
nothing between the socket and the decoder that holds frames. Latency is the reason this pipeline
exists and is tested separately in [11-latency.md](11-latency.md); this file tests that video
**works** — negotiation, decode, aspect ratio, the overlay, and the failure paths.

Stream facts: one H.264 video track, **High Profile, Level 4.0, 800×480, yuv420p**, RTP payload
type 96, clock 90000. 800×480 is **5:3**.

> Read [README.md](README.md) first — in particular which rig can carry video at all.

**Rig:** A renders **only over TCP** (SLIRP drops inbound UDP). B and C render over UDP, which is
the real transport. **Latency is never judged on rig A.**

---

### 07.1 First frame reaches the glass

- **SETUP:** Connected with video enabled. Rig A: transport TCP. Rig B/C: transport UDP.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  # connect, then:
  sleep 8
  "$ADB" exec-out screencap -p > 07_1_video.png
  ```
- **EXPECTED:** The video pane shows moving picture. The connect spinner is gone.
- **VERIFY:** `07_1_video.png` shows the stream (with `--source clock` the burned-in frame counter
  is visible and incrementing between two screenshots taken a second apart).
- **PASS/FAIL:** PASS if a picture renders. FAIL if the pane stays black while the simulator reports
  a session. **BLOCKED** on rig A if transport is left on UDP — that combination cannot work and is
  not a bug (see README §1).

---

### 07.2 The spinner ends at the first frame, not at "prepared"

Those differ by seconds, and a spinner that lies about being finished is worse than no spinner.

- **SETUP:** Disconnected. Watch the screen through a connect.
- **STEPS:** Connect, and screenshot every second for the first 10 seconds.
  ```bash
  for i in $(seq 1 10); do "$ADB" exec-out screencap -p > 07_2_$i.png; sleep 1; done
  ```
- **EXPECTED:** A progress indicator is visible from connect until the moment picture appears, and
  disappears only then. There is no window showing a blank black pane with no spinner.
- **VERIFY:** Step through the frames: every pre-video frame has the indicator.
- **PASS/FAIL:** PASS if the spinner covers the whole pre-first-frame period. FAIL if it clears
  early leaving unexplained black.

---

### 07.3 The picture is letterboxed, never stretched

- **SETUP:** Video rendering.
- **STEPS:** Look at `07_1_video.png` and measure the picture area.
- **EXPECTED:** The video is rendered at **5:3** with black bars as needed. On a phone in landscape
  the picture is noticeably squarer than the screen; that is correct. Circles in the test pattern
  are round, not oval.
- **VERIFY:** Measure the rendered picture: width/height ≈ 1.667. Compare against the panel edge.
- **PASS/FAIL:** PASS if the aspect ratio is preserved. FAIL if the image fills the pane by
  stretching — a distorted chart is a wrong chart.

---

### 07.4 Side-by-side layout: video left, controls right, full height

- **SETUP:** Connected with video on.
- **EXPECTED:** Video occupies the weighted left region; the control panel is a fixed-width column
  on the right, full height, always visible. The 5:3 picture would otherwise waste the phone's
  landscape width, and the keypad must not require leaving the video.
- **VERIFY:** `07_1_video.png` shows both simultaneously; the panel is not overlaid on the video.
- **PASS/FAIL:** PASS if both are visible together. FAIL if the panel overlaps the picture or is
  only reachable by hiding video.

---

### 07.5 The stats overlay reports the pipeline honestly

- **SETUP:** Video rendering.
- **STEPS:** Read the overlay text in the corner of the video pane.
- **EXPECTED:** It shows frames per second, queue depth, decode milliseconds, dropped access units
  and discontinuity ("gap") counts, plus a `TCP (sim)` marker when the simulator-only transport is
  in use. In steady state: **fps ≈ the source rate** (15 with the simulator), **queue depth 0–2**,
  decode time in the low tens of milliseconds.
- **VERIFY:** Compare the overlay's fps against the source; watch queue depth over 30 seconds.
- **PASS/FAIL:** PASS if the numbers are plausible and stable. **FAIL if the queue depth climbs and
  stays climbing** — that is latency accumulating, the exact failure this pipeline is designed to
  prevent, and it should instead drop to the newest frame.

---

### 07.6 Frames are dropped rather than queued when the decoder falls behind

- **SETUP:** Video rendering. Induce load (start several apps, or run a busy loop via `adb shell`).
- **STEPS:** Watch the overlay's `drop` and `q` values under load.
- **EXPECTED:** Under pressure the drop count rises while the queue depth stays ≤ 2. Latency that
  accumulates is never recovered, so a dropped frame is always preferred over a late one.
- **VERIFY:** Queue depth does not grow monotonically; drops increase instead.
- **PASS/FAIL:** PASS if the queue stays shallow under load. FAIL if it deepens without bound.

---

### 07.7 Packet loss resynchronises at the next keyframe

- **SETUP:** Rig B or C (UDP), video rendering.
- **STEPS:** Induce loss — walk to the edge of Wi-Fi range, or shape the host's interface. Watch
  the overlay's `gap` counter and the picture.
- **EXPECTED:** Gaps are counted. Torn frames are **never displayed**: after a loss the decoder
  waits for the next IDR and resumes cleanly. A brief freeze is acceptable; macroblock garbage is
  not.
- **VERIFY:** The gap counter increments and the picture recovers cleanly.
- **PASS/FAIL:** PASS if loss produces a clean brief freeze then recovery. FAIL if corrupt frames
  are rendered. **BLOCKED** on rig A.

---

### 07.8 Control-only mode and returning to video

- **SETUP:** Connected with video on.
- **STEPS:**
  ```bash
  tap_text "Remote"; sleep 2; "$ADB" exec-out screencap -p > 07_8_off.png
  tap_text "Mirror";  sleep 8; "$ADB" exec-out screencap -p > 07_8_on.png
  ```
- **EXPECTED:** Video stops and the pipeline is torn down (no decoder left running); the keypad
  takes the screen. Turning it back on re-establishes RTSP and renders again.
- **VERIFY:** `07_8_off.png` has no video pane; `07_8_on.png` shows picture again. No crash across
  the cycle.
- **PASS/FAIL:** PASS if video can be cycled off and on repeatedly. FAIL if the second start never
  renders (a leaked decoder or surface) or the app crashes.

---

### 07.9 A wrong transport fails visibly, not silently

- **SETUP:** Rig A with transport set to **UDP** (the combination that cannot work), or rig C with
  transport set to TCP.
- **STEPS:** Connect and wait 20 seconds.
- **EXPECTED:** The video pane reports a failure and that it is retrying, rather than sitting black
  forever with no explanation. Control still works throughout.
- **VERIFY:** The pane shows the retry message; keys still reach the display.
- **PASS/FAIL:** PASS if the failure is stated on screen and control is unaffected. FAIL if the app
  hangs, crashes, or gives no indication. (On real hardware a TCP-interleaved SETUP is accepted and
  then delivers nothing forever — the app must not present that as "connecting".)

---

### 07.10 RTSP session keepalive

- **SETUP:** Video rendering.
- **STEPS:** Leave it running for **three minutes** without touching anything.
- **EXPECTED:** Video is still playing. The client sends periodic keepalives well inside the
  server's advertised session timeout.
- **VERIFY:** Picture still updating after three minutes; frame counter still advancing.
- **PASS/FAIL:** PASS if the session survives. FAIL if video stops after ~60 s — that is a missing
  or too-slow keepalive.

---

### 07.11 SDP and depacketization correctness (unit-level)

The parsing that everything above depends on is covered by fast JVM tests; run them when changing
the video path.

- **STEPS:**
  ```bash
  cd openhelm && ./gradlew :protocol:test --tests '*Sdp*' --tests '*RtpH264*' --rerun-tasks
  ```
- **EXPECTED:** All pass: payload type/control/clock parsing, SPS/PPS extraction from
  `sprop-parameter-sets`, rejection of non-H.264 and audio-only SDP, single-NAL / FU-A / STAP-A
  reassembly, and sequence-gap handling that drops the torn unit and reports a discontinuity.
- **PASS/FAIL:** PASS on zero failures.
