# 09 — Connection lifecycle

Connecting, losing the connection, recovering from it, and disconnecting on purpose. The design
rules being verified: one supervised loop (never a thread per retry), a generous connect watchdog,
state published from one place, and a disconnect that stays disconnected.

> Read [README.md](README.md) first.

**Rig:** A, B or C.

---

### 09.1 Explicit disconnect returns to the connect screen and stays there

- **SETUP:** Connected, display still reachable.
- **STEPS:**
  ```bash
  tap_text "Disconnect"; sleep 1; tap_text "Disconnect"   # second tap confirms
  sleep 10
  "$ADB" exec-out screencap -p > 09_1_disconnected.png
  tail -3 emulator/emu.log
  ```
- **EXPECTED:** The control socket closes (the simulator logs `client disconnected`), the app shows
  the connect screen reading **Disconnected** with a **Scan again** button, and it does **not**
  reconnect on its own even though the display is still there.
- **VERIFY:** Screenshot after 10 s still shows the connect screen. `emu.log` shows the disconnect
  and no immediate reconnection.
- **PASS/FAIL:** PASS if the disconnect sticks. FAIL if the app auto-reconnects — that makes the
  button look broken (a real defect that was fixed; this is its regression test).

---

### 09.2 Connecting to an unreachable address fails gracefully

- **SETUP:** Manual connect to `192.0.2.99` (TEST-NET-1, guaranteed unroutable).
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  # enter 192.0.2.99 on the manual screen and tap Connect
  sleep 20
  "$ADB" exec-out screencap -p > 09_2_unreachable.png
  "$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"
  ```
- **EXPECTED:** The app shows a connecting/reconnecting state with a reason, retries with backoff,
  and remains responsive. It does not crash, does not ANR, and does not silently sit on a blank
  screen. The connect watchdog is generous (45 s+) — slow associations on boat Wi-Fi are real, and
  a tight timeout aborts connects that would have succeeded.
- **VERIFY:** The app is still foreground; the screen names the failure. No `FATAL` in logcat.
- **PASS/FAIL:** PASS if the failure is visible and the app stays usable. FAIL on a crash, freeze,
  or an unexplained blank screen.

---

### 09.3 Backoff is bounded and does not storm

- **SETUP:** 09.2 still retrying against the dead address.
- **STEPS:**
  ```bash
  PID=$("$ADB" shell pidof $PKG | tr -d '\r')
  for i in 1 2 3; do
    echo "sample $i: $("$ADB" shell cat /proc/$PID/status | grep -i Threads)"
    sleep 20
  done
  ```
- **EXPECTED:** Retries space out (roughly 1 s, 2 s, 4 s … capped around 15 s) rather than hammering
  continuously. Thread count is flat across samples.
- **VERIFY:** `Threads:` is stable — not climbing sample to sample.
- **PASS/FAIL:** PASS if retries are spaced and threads bounded. FAIL if the count grows (the
  original app spawned a thread per failed frame, forever).

---

### 09.4 Mid-session drop is detected and recovered

- **SETUP:** Connected and working. Then stop the simulator (or power-cycle the MFD's Wi-Fi).
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  # --- stop the simulator ---
  sleep 8
  "$ADB" exec-out screencap -p > 09_4_dropped.png
  # --- restart the simulator ---
  sleep 20
  "$ADB" exec-out screencap -p > 09_4_recovered.png
  ```
- **EXPECTED:** The drop is noticed (the read side exists only to detect the peer closing) and the
  UI shows a reconnecting state with a reason. When the display returns, the app reconnects by
  itself without the user going back to discovery — a Wi-Fi blip mid-passage must not dump them out.
- **VERIFY:** `09_4_dropped.png` shows the reconnecting state; `09_4_recovered.png` shows a working
  session again.
- **PASS/FAIL:** PASS if it detects the drop and recovers automatically. FAIL if it claims to still
  be connected, or requires a manual reconnect.

---

### 09.5 Keys pressed while disconnected are dropped, not replayed

- **SETUP:** Connected, then stop the simulator so the socket is dead but the remote screen is up.
- **STEPS:**
  ```bash
  for i in $(seq 1 10); do tap_text "Menu"; done
  # --- restart the simulator, let it reconnect ---
  sleep 20
  tail -20 emulator/emu.log
  ```
- **EXPECTED:** On reconnect the simulator sees **no burst** of the ten queued Menu presses. Stale
  input is discarded: by the time a reconnect succeeds, those key presses are old, and replaying
  them to a chartplotter is worse than losing them.
- **VERIFY:** The log after reconnection shows only the connection, not a flood of MENU pairs.
- **PASS/FAIL:** PASS if stale frames are dropped. FAIL if they are queued and replayed.

---

### 09.6 Connection state is consistent across the UI

- **SETUP:** Cycle through: scanning → connecting → connected → reconnecting → disconnected.
- **EXPECTED:** Every screen agrees, because state is published from one place rather than
  hand-rolled per screen. The status line, the video pane and the connect screen never disagree
  about whether there is a connection.
- **VERIFY:** Screenshot each transition; check for contradictions such as a "Connected" status line
  above a pane reporting a retry.
- **PASS/FAIL:** PASS if the UI is always self-consistent. FAIL on contradictory states.

---

### 09.7 Failure states are distinguishable

Two failures look identical to a user and are not: **control works but video does not** is a usable
app; **nothing is connected** is not.

- **SETUP:** Produce each: (a) connect with a deliberately wrong video transport so video fails
  while control works; (b) disconnect entirely.
- **EXPECTED:** (a) shows a connected status line with a video-specific failure in the pane, and the
  keypad works. (b) shows the connect screen. The user can tell which is which **without relying on
  colour alone** — each state is named in words.
- **VERIFY:** Screenshots of both; each carries a distinguishing word, not just a colour change.
- **PASS/FAIL:** PASS if the two are clearly distinguished in text. FAIL if both present the same
  generic error.

---

### 09.8 Reconnect after the display is renamed or moved

- **SETUP:** A named, remembered display. Change its address (reconfigure the simulator to a
  different port, or let DHCP change on the boat).
- **EXPECTED:** The old entry no longer connects; the probe skips it after its bounded timeout and
  the scan finds the display at its new address, which is then remembered as a new entry.
- **VERIFY:** The app still gets connected without user intervention (rig B/C), or via Manual
  connect (rig A).
- **PASS/FAIL:** PASS if a moved display is still reachable without wiping app data. FAIL if the
  app is stuck retrying the stale address forever.

> **Known limitation, not a failure:** names are keyed by host address, so a display that changes
> address arrives as a new, unnamed entry. Record it; the old entry can be removed via Manage
> displays.

---

### 09.9 Rapid connect/disconnect cycling is stable

- **SETUP:** Connected.
- **STEPS:**
  ```bash
  PID=$("$ADB" shell pidof $PKG | tr -d '\r')
  echo "before: $("$ADB" shell cat /proc/$PID/status | grep -i Threads)"
  for i in $(seq 1 8); do
    tap_text "Disconnect"; sleep 1; tap_text "Disconnect"; sleep 2
    tap_text "Scan again"; sleep 6
  done
  PID=$("$ADB" shell pidof $PKG | tr -d '\r')
  echo "after:  $("$ADB" shell cat /proc/$PID/status | grep -i Threads)"
  tail -30 emulator/emu.log | grep -c "client connected"
  ```
- **EXPECTED:** Each cycle produces exactly one connection. No socket, thread or decoder leak; the
  app stays responsive.
- **VERIFY:** Thread count after ≈ before. Connection count matches cycle count — not double.
- **PASS/FAIL:** PASS if cycling is clean. FAIL on leaks, duplicate simultaneous connections, or a
  crash.
