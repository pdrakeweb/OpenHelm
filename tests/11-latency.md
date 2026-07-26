# 11 — Latency

**This is the reason OpenHelm exists.** The app it replaces ran 6–7 seconds behind the display it
was mirroring, and roughly 4.6 s of that was the stock player's own buffering, which has no API to
tune. The target here is **under 500 ms glass-to-glass**.

Because latency is the headline claim, this file is the strictest in the suite — and the easiest to
get a confidently wrong answer from.

> Read [README.md](README.md) first.

---

## ⚠️ Rig rules, before anything else

**Never measure latency on an AVD.** An emulator's only working video path is segment-buffered and
sits **seconds** behind live *by construction* — the same 6–10 s range as the bug being chased. An
AVD will hand you a plausible-looking number that is really just the rig, and "confirm" a
regression that does not exist. Rig A is **BLOCKED** for every test in this file, without exception.

| Rig | Verdict |
|---|---|
| A — AVD | **BLOCKED.** Not "less accurate": actively misleading. |
| B — real phone + simulator over Wi-Fi | ✅ Indicative. Real UDP, real Wi-Fi, known-good source. |
| C — real phone + real display | ✅ Authoritative. The number that counts. |

---

### 11.1 Measure against the simulator's burned-in frame counter (rig B)

The `clock` source burns the frame number into every frame, and FFmpeg publishes in real time, so
the frame being generated at any instant is computable from the publish start time. No clock
synchronisation and no second client are needed.

- **SETUP:** Rig B. Phone and host on the same Wi-Fi.
  ```bash
  cd emulator
  python -m mfd_emulator --no-console --source clock --log-file emu.log
  ```
  In the app: **Manual connect** → the host's LAN IP → transport **UDP** → Connect. Confirm video
  renders and the big counter is visible.
- **STEPS:**
  ```bash
  python scripts/measure_avd_latency.py --log emu.log
  ```
  The script prints the **source frame** at the instant it grabbed a screenshot, and saves the
  screenshot. Read the big burned-in number off the image.
  ```
  latency_seconds = (source_frame − displayed_frame) / 15
  ```
- **EXPECTED:** Under **500 ms** — that is 7–8 frames at 15 fps. The measurement rig itself
  contributes under ~0.5 s and biases the result *high*, so treat the output as ±0.5 s.
- **VERIFY:** Repeat three times and take the spread. A steady-state figure matters more than any
  single sample; take them at least 30 s after the stream starts.
- **PASS/FAIL:** PASS if the app is within ~8 frames of the source. **FAIL at anything approaching
  seconds** — that is the defect this project exists to eliminate. Record the number either way.
  **BLOCKED** on rig A.

---

### 11.2 Latency is steady, not creeping

A buffer that fills slowly looks fine for ten seconds and useless after two minutes.

- **SETUP:** 11.1, video running.
- **STEPS:** Measure at 30 s, 2 min, and 5 min after the stream starts. Watch the overlay's queue
  depth throughout.
- **EXPECTED:** The three measurements are within a frame or two of each other. Queue depth stays
  0–2 the whole time. Latency that accumulates is never recovered, so the pipeline drops to the
  newest frame rather than letting the queue deepen.
- **VERIFY:** Three comparable numbers; a flat queue depth.
- **PASS/FAIL:** PASS if latency is flat over five minutes. FAIL if it climbs — the drop-to-newest
  policy has regressed.

---

### 11.3 Recovery after a stall does not add permanent latency

- **SETUP:** Video running, measured.
- **STEPS:** Interrupt the stream briefly (block Wi-Fi for ~5 s, or pause the publisher), restore
  it, wait 30 s, then measure again.
- **EXPECTED:** After recovery the app is back to its pre-stall latency. On resume it skips forward
  to the newest picture rather than replaying the backlog.
- **VERIFY:** Post-recovery measurement matches the pre-stall one.
- **PASS/FAIL:** PASS if latency returns to baseline. FAIL if each stall permanently adds delay.

---

### 11.4 Boat-side measurement against a real display (rig C)

A real display has no burned-in counter, so use **its response to a command** as the event.

- **SETUP:** Rig C. Phone connected, video rendering. Stand where you can see the MFD and the phone
  at once.
- **STEPS:** Press a control that changes the picture unmistakably — the range keys are ideal —
  and record both screens together (a video of both, or a burst of photos).
  ```bash
  # from a laptop on the same network, as an independent cross-check:
  ffplay -rtsp_transport udp -fflags nobuffer -flags low_delay -framedrop \
      rtsp://<mfd-ip>:8554/RAYMARINEMFD
  ```
- **EXPECTED:** The change appears on the phone within ~500 ms of appearing on the MFD. The
  laptop's low-latency reference client gives an independent bound: if FFmpeg is ~1 s behind the
  MFD and the app matches it, the app is not the bottleneck.
- **VERIFY:** Count frames between the two screens in the recording (at 30 fps, 15 frames = 0.5 s).
- **PASS/FAIL:** PASS under ~500 ms. FAIL if the app trails the reference client by seconds. If
  **both** app and FFmpeg trail the MFD by seconds, the display itself is buffering and no
  client-side work will fix it — record that as a finding about the hardware, not a bug.
  **BLOCKED** without a real display.

---

### 11.5 Treat a latency regression as a build failure

- **SETUP:** Any measured baseline.
- **EXPECTED:** The overlay reports fps, queue depth and decode time continuously so a regression is
  visible without ceremony. The pipeline's rules that keep the number low, any of which would show
  up here if broken:
  - no jitter buffer beyond about one frame interval;
  - output buffers released for render **immediately**, never scheduled against a stream clock;
  - low-latency decode requested, codec at realtime priority;
  - queue capped at ~2 access units, dropping to the newest beyond that;
  - resynchronise at the next keyframe after loss rather than replaying.
- **VERIFY:** Read the overlay during any of the tests above.
- **PASS/FAIL:** Record the measured figure in the run notes every time. A number that has grown
  since the last run is a **failure**, even if it is still under target — it means one of the rules
  above has quietly regressed.
