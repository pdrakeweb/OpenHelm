# 06 — The control channel (RRC)

The control channel is a small binary protocol over one TCP socket. It is the part of the app that
works even when video does not, and it is fully verifiable against the simulator, which decodes
every frame into a symbolic log line.

Frame layout — 9-byte header plus payload:

```
offset  bytes         meaning
0..3    45 43 52 52   magic "ECRR"
4       01            constant
5       vv            version byte (0x10 on the reference unit)
6       op            opcode: 1 button, 2 zoom, 3 touch
7..8    ll hh          payload length, little-endian
9..     ....          payload
```

Keycodes are Windows virtual-key values: Left 37, Up 38, Right 39, Down 40, OK 13, Home 118,
Menu 120, Back 27, Range-out 33, Range-in 34, Switch 122, WPT 119.

> Read [README.md](README.md) first. The oracle for everything here is `tail -f emulator/emu.log`.

**Rig:** A, B (simulator decodes frames) or C (real display reacts — the only rig that proves
*behaviour*).

---

### 06.1 Every key sends the right keycode, with separate press and release

- **SETUP:** Connected. `tail -f emulator/emu.log` in another window.
- **STEPS:** Tap each control on the side panel and the dial in turn.
  ```bash
  "$ADB" logcat -c
  for K in "Home" "Menu" "Back" "Zoom in" "Zoom out" "Pane" "Waypoint"; do
    echo "=== $K ==="; tap_text "$K"; sleep 1
  done
  tail -40 emulator/emu.log
  ```
- **EXPECTED:** Each tap produces **two** log lines — `down` then `up` — naming the right key:
  `HOME`, `MENU`, `BACK`, `RANGE_IN`, `RANGE_OUT`, `SWITCH`, `WPT`.
- **VERIFY:** The log shows a matched down/up pair per tap with the correct symbolic name and no
  extra frames.
- **PASS/FAIL:** PASS if all seven map correctly with paired down/up. FAIL on a wrong keycode, a
  missing release, or a duplicate frame.

---

### 06.2 The dial's four sectors and OK hub

- **SETUP:** Connected; the dial is the large round control on the side panel.
- **STEPS:** Tap the top, right, bottom, left sectors and the centre in turn (derive coordinates
  from a screenshot; the dial's hub is OK and the ring around it is the rotary).
- **EXPECTED:** `UP`, `RIGHT`, `DOWN`, `LEFT`, `OK/ENTER`, each as a down/up pair.
- **VERIFY:** `emu.log` names each direction correctly. A tap near the sector boundary should still
  resolve to one unambiguous direction.
- **PASS/FAIL:** PASS if all five decode correctly. FAIL if a sector sends the wrong direction, or
  if a tap in the hub sends a direction instead of OK.

---

### 06.3 A hold is transmitted as a hold — the single most important control test

The display implements auto-repeat itself. One DOWN with a delayed UP produces continuous cursor
movement; the gap between them is the only control the user has. Measured on real hardware: 20
discrete taps moved the cursor ~23 px (≈1 px each), while a **2-second hold** drove it to the
screen edge and then panned the chart ~20 nautical miles.

- **SETUP:** Connected.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  # a long same-point swipe is a press-and-hold
  "$ADB" shell input swipe <dial_right_x> <dial_right_y> <dial_right_x> <dial_right_y> 2000
  sleep 1
  tail -4 emulator/emu.log
  ```
- **EXPECTED:** Exactly one `RIGHT down` followed by one `RIGHT up`, with their timestamps
  **≈2.0 s apart**.
- **VERIFY:** Subtract the two timestamps in `emu.log`; it must be ~2 s, not milliseconds.
- **PASS/FAIL:** PASS if the gap matches the hold duration within ~100 ms. **FAIL if the release
  follows immediately** — that means a synthetic UP is being generated, which silently destroys
  coarse cursor movement and is the defect this test exists to catch.

---

### 06.4 Release is always sent, even on a cancelled gesture

A key left logically held would auto-repeat forever on the display.

- **SETUP:** Connected.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  # press on a key then slide well off it before lifting
  "$ADB" shell input swipe <key_x> <key_y> $((<key_x> + 600)) <key_y> 800
  sleep 1
  tail -4 emulator/emu.log
  ```
- **EXPECTED:** A `down` and a matching `up`. The gesture being cancelled (finger dragged away)
  must still produce the release.
- **VERIFY:** The pair is present and balanced.
- **PASS/FAIL:** PASS if every down has an up. FAIL if a cancelled press leaves the key held.

---

### 06.5 The rotary ring sends zoom, not a cursor move

Opcode 2 carries what look like coordinates but **changes the chart range**. Verified on hardware:
sending plausible screen positions stepped the chart 5 nm → 4 nm → 2500 ft while the cursor never
moved. Negative values zoom out.

- **SETUP:** Connected.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  # drag around the dial's outer ring
  "$ADB" shell input swipe <ring_x1> <ring_y1> <ring_x2> <ring_y2> 600
  sleep 1
  tail -8 emulator/emu.log
  ```
- **EXPECTED:** A series of `pointer cursor dx=… dy=…` lines (the simulator's name for opcode 2),
  one per rotation step, with the accumulated value climbing. Rotating the other way produces
  negative steps.
- **VERIFY:** The log shows opcode-2 frames appearing progressively during the drag, not one
  frame at the end and not opcode-1 key frames.
- **PASS/FAIL:** PASS if ring rotation emits stepped zoom frames in both directions. FAIL if it
  emits key frames, or nothing. On rig C additionally confirm the **chart range changes and the
  cursor does not move**.

---

### 06.6 Frames are dropped, never queued, when the socket is down

The original app re-sent a failed frame from a newly spawned thread, forever — a thread-spawn storm
on any write error. The rewrite drops the frame and lets one supervised loop reconnect.

- **SETUP:** Connected, then kill the simulator's control server (or stop the simulator) while the
  remote screen is still showing.
- **STEPS:**
  ```bash
  PID=$("$ADB" shell pidof $PKG | tr -d '\r')
  echo "threads before: $("$ADB" shell cat /proc/$PID/status | grep -i Threads)"
  # --- stop the simulator here ---
  for i in $(seq 1 20); do tap_text "Menu"; done
  sleep 3
  PID=$("$ADB" shell pidof $PKG | tr -d '\r')
  echo "threads after:  $("$ADB" shell cat /proc/$PID/status | grep -i Threads)"
  "$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"
  "$ADB" exec-out screencap -p > 06_6_afterdrop.png
  ```
- **EXPECTED:** Presses while disconnected are silently dropped. The thread count stays flat. The
  app stays alive and responsive, showing a reconnecting state rather than freezing.
- **VERIFY:** `Threads:` after ≈ before (a small delta is fine; tens or hundreds is not, and it must
  not keep climbing on repeat sampling). `pidof` still returns a pid. The screenshot shows a live UI.
- **PASS/FAIL:** PASS if frames are dropped with a bounded thread count and a live app. FAIL if
  threads storm, the app freezes or dies, or stale keys are replayed on reconnect (a chartplotter
  receiving a burst of old key presses is worse than losing them).

---

### 06.7 Socket options and single-connection discipline

- **SETUP:** Connected.
- **STEPS:**
  ```bash
  "$ADB" shell "cat /proc/net/tcp" | wc -l        # before/after comparison
  grep -c "client connected" emulator/emu.log
  ```
- **EXPECTED:** Exactly **one** control connection per session — not one per key press. The socket
  uses `TCP_NODELAY` (frames must not be Nagle-batched; a key press is latency-sensitive) and
  `SO_KEEPALIVE`.
- **VERIFY:** `emu.log` shows a single `client connected` for the session. Pressing many keys adds
  no further connections.
- **PASS/FAIL:** PASS with one connection per session. FAIL if each frame opens a socket.

---

### 06.8 Control works with video off

Control-only mode is a first-class mode: it is the fallback whenever video is the broken half.

- **SETUP:** Connected.
- **STEPS:**
  ```bash
  tap_text "Video off"
  sleep 2
  "$ADB" exec-out screencap -p > 06_8_controlonly.png
  "$ADB" logcat -c; tap_text "Menu"; sleep 1; tail -3 emulator/emu.log
  ```
- **EXPECTED:** The video pane disappears, the full-screen keypad shows, and keys still produce
  frames.
- **VERIFY:** The screenshot shows the keypad without video; `emu.log` shows the `MENU` pair.
- **PASS/FAIL:** PASS if control is unaffected by video being off. FAIL if disabling video also
  breaks the control channel.

---

### 06.9 On real hardware: the cursor actually moves

Everything above proves the bytes are right. Only this proves the app works.

- **SETUP:** Rig C, connected to a real MFD with a chart on screen.
- **STEPS:** Tap a direction key 20 times, then hold the same direction for 2 seconds. Watch the
  MFD, not the phone.
- **EXPECTED:** 20 taps nudge the cursor a barely-perceptible distance (~1 px each, ~23 px total).
  The 2-second hold sweeps the cursor to the edge and then pans the chart substantially.
- **VERIFY:** By eye on the MFD. Photograph before/after if recording the result.
- **PASS/FAIL:** PASS if taps and holds behave as described. FAIL if a hold behaves like a tap
  (synthetic release — see 06.3) or nothing moves. **BLOCKED** on rigs A and B: the simulator logs
  frames but does not model a cursor.
