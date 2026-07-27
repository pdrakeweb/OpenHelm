# 13 — Simulation mode

A toggle in Settings that drops into a fake connected session with **no network involved at
all** — not even the MFD simulator. Video is generated on the device; the keypad and dial are fully
tappable and give real press/release feedback; nothing is sent anywhere. It exists so the app can
be explored, demoed, or screenshotted without a display, an emulator, or a boat.

The one rule that matters most: **the toggle is never remembered.** It is plain in-memory
`ViewModel` state, not written through `EndpointStore`/DataStore like everything else in Settings.
Every launch starts in real mode, always.

> Read [README.md](README.md) first.

**Rig:** none needed — this is the one feature area that works with **no simulator process and no
MFD**. **Arch:** any.

---

### 13.1 Reaching the toggle

- **SETUP:** Connect screen showing.
- **STEPS:**
  ```bash
  "$ADB" shell input tap 2497 113      # overflow (kebab), top-right
  sleep 1
  tap_text "Settings"
  sleep 2
  "$ADB" exec-out screencap -p > 13_1_settings.png
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -i 'text="'
  ```
- **EXPECTED:** A **Simulation mode** card near the top of Settings, above the **Displays** section,
  with an explanatory line and a switch. The switch is **off** by default.
- **VERIFY:** The dump contains `Simulation mode`; the switch node reports `checked="false"`.
- **PASS/FAIL:** PASS if the toggle is present, above Displays, and off by default. FAIL if it is
  missing, buried below the displays list, or defaults on.

---

### 13.2 Enabling the toggle enters a simulated session immediately

- **SETUP:** 13.1, switch off.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  tap_text "Simulation mode"           # or the switch itself — either should work
  sleep 2
  "$ADB" exec-out screencap -p > 13_2_entered.png
  "$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"
  ```
  If tapping the label text doesn't flip the switch, tap the switch control directly:
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  # find the Switch node's bounds and tap its centre
  ```
- **EXPECTED:** The app leaves Settings immediately and shows a remote-shaped screen: a status line
  reading **Simulated display**, moving colour-bar video on the left, the same side panel (Home,
  Menu, dial, Back, Range, Switch/WPT, mode-switch key) on the right. No `am start`/relaunch needed —
  flipping the switch alone performs the transition.
- **VERIFY:** `13_2_entered.png` shows the simulated remote, not Settings. `topResumedActivity` is
  still `.MainActivity` (no new Activity). No crash in logcat.
- **PASS/FAIL:** PASS if the switch transitions straight into the simulated remote. FAIL if it stays
  on Settings, requires an extra tap elsewhere, or crashes.

---

### 13.3 The simulated video is unmistakably fake

Nobody should be able to confuse this with a real connection.

- **SETUP:** 13.2.
- **STEPS:** Take two screenshots a couple of seconds apart.
  ```bash
  "$ADB" exec-out screencap -p > 13_3_a.png
  sleep 2
  "$ADB" exec-out screencap -p > 13_3_b.png
  ```
- **EXPECTED:** The video pane shows a generated chart scene — data bar, coastline, depth
  contours and soundings, a dashed route with a vessel running down it, a cursor, and a menu
  column — plus a **`SIMULATED`**
  label with a frame counter overlaid — visibly distinct from the real video pane's stats overlay
  (`fps · q · dec · drop · gap`), which must **not** appear here (there is no decoder running to
  report on). The frame counter has advanced between the two screenshots — it is animating, not a
  static image. The picture is letterboxed at 5:3, matching the real video pane's aspect ratio.
- **VERIFY:** Both screenshots show the `SIMULATED` label; the frame counter differs between them;
  neither shows `fps`/`dec`/`drop`/`gap`.
- **PASS/FAIL:** PASS if the simulated feed is animated, labelled, and visually distinct from a real
  session's overlay. FAIL if it is static, unlabelled, or could be mistaken for real video/stats.

---

### 13.4 Keypad and dial are fully tappable with real feedback

- **SETUP:** 13.2.
- **STEPS:** Tap each named key and each dial sector; watch for press-state feedback (the key's
  background changes while held, per [Keypad.kt](../app/src/main/kotlin/dev/openhelm/app/ui/Keypad.kt)'s
  existing pressed-state handling).
  ```bash
  for K in "Home" "Menu" "Back" "Zoom in" "Zoom out" "Pane" "Waypoint"; do
    tap_text "$K"; sleep 0.5
  done
  "$ADB" exec-out screencap -p > 13_4_after.png
  ```
- **EXPECTED:** Every key responds visually to a tap (the same press/release colour change as a real
  session), including a 2-second hold behaving like a hold, not a synthetic tap. Nothing crashes.
  This is the same [MfdKeyButton](../app/src/main/kotlin/dev/openhelm/app/ui/Keypad.kt) used in a
  real session, so its behaviour should be identical.
- **VERIFY:** By eye during the taps; no crash afterward.
- **PASS/FAIL:** PASS if every control gives real press feedback. FAIL if any key is inert or the
  app crashes on input.

---

### 13.5 No network traffic is produced by the simulated controls

The controls must be genuinely inert on the wire — this is what makes simulation mode safe to use
anywhere, including on a boat's real Wi-Fi without risk of confusing a real display.

- **SETUP:** 13.2. Ideally with the MFD simulator (`emulator/`) also running, specifically so there
  is something listening that *would* log a frame if one were sent.
  ```bash
  cd emulator && python -m mfd_emulator --no-console --log-file emu.log &
  ```
- **STEPS:**
  ```bash
  tail -f emulator/emu.log &      # or just note the line count before/after
  # in the app: press several keys, rotate the dial, hold a direction
  sleep 3
  wc -l emulator/emu.log
  ```
- **EXPECTED:** No new lines in `emu.log` regardless of how many keys are pressed — simulation mode
  never opens a socket to anything, real or simulated. (This holds even though the simulator happens
  to be running: the app in simulation mode has no endpoint to send to, so `keyDown`/`keyUp` calls
  fall through their existing no-connection no-op path.)
- **VERIFY:** The line count is identical before and after.
- **PASS/FAIL:** PASS if no control frame is ever emitted. FAIL if any frame reaches the simulator.

---

### 13.6 The Mirror / Remote switch works the same as a real session

- **SETUP:** 13.2, side-by-side.
- **STEPS:**
  ```bash
  tap_text "Remote"; sleep 1; "$ADB" exec-out screencap -p > 13_6_off.png
  tap_text "Mirror";  sleep 1; "$ADB" exec-out screencap -p > 13_6_on.png
  tap_text "Remote"; sleep 1; "$ADB" exec-out screencap -p > 13_6_fullscreen.png
  ```
- **EXPECTED:** **Remote** replaces the side-by-side view with the full-screen keypad (simulated
  video pauses/disappears, not just hidden behind the panel); **Mirror** restores it. Both
  segments are visible at once with the current one marked, so neither has to be inferred from
  a verb. The simulated status bar carries the same control as the real remote screen's
  behaviour ([08-touch-and-gestures.md](08-touch-and-gestures.md) 08.7).
- **VERIFY:** The three screenshots show the three states correctly.
- **PASS/FAIL:** PASS if mode switching matches the real session's behaviour. FAIL if either control
  is inert or the simulated video keeps running full-screen alongside the keypad.

---

### 13.7 Exiting simulation

- **SETUP:** 13.2.
- **STEPS:**
  ```bash
  tap_text "Exit simulation"
  sleep 2
  "$ADB" exec-out screencap -p > 13_7_exited.png
  ```
- **EXPECTED:** Returns to the real connect screen — **Scanning** (or whatever the real auto-connect
  state legitimately is), not Settings, not a frozen simulated frame.
- **VERIFY:** `13_7_exited.png` shows the real connect screen.
- **PASS/FAIL:** PASS if exiting returns cleanly to the real flow. FAIL if it leaves any simulated UI
  on screen or crashes.

---

### 13.8 Back exits full-screen before it exits simulation

Mirrors the real remote screen's two-step Back ([08.8](08-touch-and-gestures.md)); simulation must
behave the same way rather than exiting in one step.

- **SETUP:** 13.2, then switch to full-screen (13.6).
- **STEPS:**
  ```bash
  "$ADB" shell input keyevent 4; sleep 1; "$ADB" exec-out screencap -p > 13_8_first.png
  "$ADB" shell input keyevent 4; sleep 2; "$ADB" exec-out screencap -p > 13_8_second.png
  ```
- **EXPECTED:** First Back returns from full-screen to side-by-side simulation (still simulating).
  Second Back exits simulation to the real connect screen.
- **VERIFY:** The two screenshots show that progression.
- **PASS/FAIL:** PASS if Back steps through exactly as described. FAIL if the first Back exits
  simulation entirely, or if Back ever exits the app.

---

### 13.9 The toggle is never persisted — the core requirement

This is the test that matters most in this file.

- **SETUP:** 13.2 (simulation running).
- **STEPS:**
  ```bash
  "$ADB" shell am force-stop $PKG
  "$ADB" shell am start -n $ACT
  sleep 5
  "$ADB" exec-out screencap -p > 13_9_restarted.png
  ```
- **EXPECTED:** The app comes back in **real mode** — the real connect screen (or auto-connecting,
  if a remembered display exists), never the simulated remote. Simulation mode does not survive a
  process restart under any circumstance.
- **VERIFY:** `13_9_restarted.png` shows real-mode UI: either the connect screen, or a real
  `Connecting…`/`Connected · <host>` status line — never `Simulated display`.
- **PASS/FAIL:** PASS if the app always restarts in real mode. **FAIL if it restarts already in
  simulation** — that would mean the flag leaked into DataStore or `SharedPreferences` somewhere,
  which is the one thing this feature must never do.

---

### 13.10 Enabling simulation while genuinely connected ends the real session first

- **SETUP:** A real connection to a reachable display (simulator or MFD).
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  "$ADB" shell input tap 2428 100          # Disconnect is not tapped here — go via the real remote's own path if one exists, otherwise back out to Settings
  # if Settings is not reachable while connected, first disconnect, then enable simulation, then
  # reconnect afterward to set up this case properly; document whichever path your build offers
  tail -3 emulator/emu.log
  ```
  (If the real remote screen has no path to Settings while connected, this test instead confirms:
  disconnect → enable simulation → the old session stays cleanly closed, never resumed underneath
  the simulated one.)
- **EXPECTED:** There is never a moment where a simulated screen sits on top of a live connection.
  Either Settings is unreachable while connected (forcing an explicit disconnect first), or entering
  simulation itself tears down the real session — the simulator/MFD sees the control socket close.
- **VERIFY:** `emu.log` shows the real disconnect; the simulated remote afterward sends no frames to
  it (13.5 covers that separately).
- **PASS/FAIL:** PASS if a real session is always fully closed before or by entering simulation. FAIL
  if a simulated session and a real one are ever both nominally active at once.
