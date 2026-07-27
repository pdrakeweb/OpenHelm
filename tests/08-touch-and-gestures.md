# 08 — Touch, gestures, and the mode switch

Two gesture paths share the video pane and must never be confused:

- **One finger** talks to the display — a tap places the cursor, a drag pans the chart, via opcode 3
  with positions normalised 0..65535 across the video area.
- **Two fingers** zoom and pan the **local view only**, through a `TextureView` matrix transform.
  A pinch is the user leaning in to read the chart, not an instruction to the display.

Opcode 3 payload: `[action][seq][x_lo][x_hi][y_lo][y_hi]`, action 1 down / 2 up / 3 move; `seq` is
0 on down and up and increments per move within one gesture.

> Read [README.md](README.md) first. Oracle: `tail -f emulator/emu.log`.

**Rig:** A/B decode frames; **C proves the display actually responds**.

> **Support note.** Opcode 3 is expected to work on the reference hardware — an **e95 HybridTouch**,
> which has a touchscreen as well as a keypad — but is unconfirmed on real units. Earlier notes
> claimed the unit was non-touch; that was wrong, inferred from the abbreviated `E9` model string.
> The arrow-key path remains a first-class fallback for genuinely keypad-only models.

---

### 08.1 A tap on the video sends down+up at one point

- **SETUP:** Connected with video rendering.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  "$ADB" shell input tap <video_centre_x> <video_centre_y>
  sleep 1
  tail -4 emulator/emu.log
  ```
- **EXPECTED:** Two lines: `touch down seq=0 norm=(x,y)` and `touch up seq=0` at the **same**
  coordinates. Tapping the centre of the pane yields roughly `(32768, 32768)` — i.e. ~50%,50%.
- **VERIFY:** The simulator prints the normalised pair and its percentage; centre ≈ 50%/50%.
- **PASS/FAIL:** PASS if a tap is one down/up pair at one point with plausible coordinates. FAIL if
  coordinates are in pixels, wildly off, or the pair is unbalanced.

---

### 08.2 Coordinates are normalised, not pixel values

- **SETUP:** As 08.1.
- **STEPS:** Tap near each corner of the **video pane** (not the screen) and read the log.
- **EXPECTED:** Top-left approaches `(0,0)`, bottom-right approaches `(65535,65535)`. Values are
  clamped to that range, never wrapped — a touch just past the edge lands on the edge, never on the
  opposite side of the chart.
- **VERIFY:** Four taps produce the four expected corners in normalised space. The simulator also
  prints the equivalent 800×480 pixel position; check it matches where you tapped proportionally.
- **PASS/FAIL:** PASS if the four corners map correctly and clamp. FAIL if any coordinate wraps or
  exceeds 65535.

---

### 08.3 A drag sends down → sequenced moves → up

- **SETUP:** Connected with video.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  "$ADB" shell input swipe <x1> <y1> <x2> <y2> 800
  sleep 1
  tail -20 emulator/emu.log
  ```
- **EXPECTED:** One `down seq=0`, several `move` lines with `seq` incrementing 1,2,3…, then one
  `up seq=0`. Move frames are throttled (~30/s) so a long drag does not flood the channel.
- **VERIFY:** Sequence numbers increase monotonically within the gesture and reset on the next one.
  Coordinates track the drag path.
- **PASS/FAIL:** PASS if the sequence structure is exactly down/moves/up with incrementing seq.
  FAIL if seq stays 0 on moves, does not reset between gestures, or hundreds of frames are emitted.

---

### 08.4 A pinch zooms the local view and sends nothing

**The critical separation test.**

- **SETUP:** Connected with video rendering.
- **STEPS:** A pinch needs two pointers, which `adb shell input` cannot inject — perform it **by
  hand** on a real device, or use a multi-touch-capable driver.
  ```bash
  "$ADB" logcat -c
  # --- perform a two-finger pinch-out on the video pane by hand ---
  sleep 1
  tail -20 emulator/emu.log
  ```
- **EXPECTED:** The picture magnifies locally and can be panned within the pane. The overlay shows a
  zoom factor (e.g. `×2.0`). **No touch frames appear in the log at all.** If a touch gesture was in
  flight when the second finger landed, it is released first (a clean `up`), and nothing further is
  sent.
- **VERIFY:** `emu.log` gains no `touch` lines during the pinch. The screenshot shows a magnified
  picture.
- **PASS/FAIL:** PASS if pinching changes only the local view and emits nothing. **FAIL if any touch
  frame is sent during a pinch** — that would send spurious taps to a chartplotter. **BLOCKED** if
  you cannot inject multitouch (state it; do not mark PASS).

---

### 08.5 While zoomed, taps still land on the right chart feature

- **SETUP:** Video zoomed to ~2× and panned off-centre.
- **STEPS:** Tap a recognisable feature in the magnified picture; read the normalised coordinates.
- **EXPECTED:** The coordinates describe the feature's position **in the source picture**, not the
  raw screen position — the local transform is inverted before normalising.
- **VERIFY:** Compare the reported 800×480 pixel equivalent against where that feature sits in the
  unzoomed frame.
- **PASS/FAIL:** PASS if the mapping accounts for zoom and pan. FAIL if a zoomed tap reports the
  untransformed position. **BLOCKED** without multitouch to establish the zoom.

---

### 08.6 Zoom snaps back and resets cleanly

- **SETUP:** Video zoomed.
- **STEPS:** Pinch back in to roughly 1×, release.
- **EXPECTED:** Near 1× the view snaps cleanly to exactly 1× with no residual pan offset; the zoom
  indicator disappears from the overlay.
- **PASS/FAIL:** PASS if it settles exactly at 1× with the picture correctly letterboxed again.
  FAIL if a small permanent offset or scale remains.

---

### 08.7 Full-screen remote and back, in both directions

- **SETUP:** Connected, side-by-side view.
- **STEPS:**
  ```bash
  tap_text "Remote"; sleep 2; "$ADB" exec-out screencap -p > 08_7_full.png
  tap_text "Mirror";    sleep 6; "$ADB" exec-out screencap -p > 08_7_back.png
  ```
- **EXPECTED:** The first tap gives a full-screen keypad with no video. The second returns to
  side-by-side and video resumes. Both directions work by button.
- **VERIFY:** The two screenshots show the two modes.
- **PASS/FAIL:** PASS if switching works both ways. FAIL if either direction is missing (in the
  predecessor app one direction worked only by accident and needed a button bolted on).

---

### 08.8 Back walks the modes rather than dropping the session

- **SETUP:** Connected in full-screen remote mode.
- **STEPS:**
  ```bash
  "$ADB" shell input keyevent 4; sleep 2; "$ADB" exec-out screencap -p > 08_8_first.png
  "$ADB" shell input keyevent 4; sleep 3; "$ADB" exec-out screencap -p > 08_8_second.png
  "$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"
  ```
- **EXPECTED:** The first Back returns to side-by-side. The second disconnects and returns to the
  connect screen. The app is never exited by surprise, and no dialog closes the app on an outside
  tap.
- **VERIFY:** The screenshots show that progression; the app is still foreground at the end.
- **PASS/FAIL:** PASS if Back steps back through modes then disconnects. FAIL if the first Back
  exits the app or tears down the session immediately.

---

### 08.9 Touch is not sent when video is off

- **SETUP:** Remote-only mode.
- **STEPS:** Tap where the video pane used to be; check the log.
- **EXPECTED:** No touch frames — there is no video area to normalise against. Keypad presses still
  work normally.
- **PASS/FAIL:** PASS if no touch frames are emitted in control-only mode. FAIL if taps on empty
  space produce opcode-3 frames.

---

### 08.10 On real hardware: does the display honour touch?

The open question this whole opcode exists to answer.

- **SETUP:** Rig C, connected to a real MFD showing a chart.
- **STEPS:** Tap a known position on the video. Watch the **MFD**.
- **EXPECTED (hypothesis):** The cursor jumps to the tapped position. The reference unit is a
  HybridTouch display, so the panel takes touch natively and there is no obvious reason for the
  remote path to be gated off.
- **VERIFY:** By eye on the MFD.
- **PASS/FAIL:** PASS if the cursor moves to the tap. **If it does not, that is a finding, not
  necessarily a bug** — record it, confirm the arrow-key fallback still positions the cursor
  (see [06](06-control-channel.md) 06.9), and note the model and serial part number. **BLOCKED** on
  rigs A and B: the simulator logs frames but models no cursor.
