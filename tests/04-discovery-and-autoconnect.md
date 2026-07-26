# 04 — Discovery, the recents-first probe, and auto-connect

The automatic path, which is the whole point of the connect screen: try the remembered displays
first (fastest route to the one actually aboard), then sweep the network, and connect to whatever
answers without asking. These networks carry exactly one display, so a chooser would be ceremony.

> Read [README.md](README.md) first — especially which rig can see mDNS at all.

**Rig:** B or C for real discovery. **Rig A cannot discover** — SLIRP NAT does not carry multicast —
so on an AVD only the recents-probe and no-display paths are testable; discovery steps are BLOCKED.

---

### 04.1 mDNS discovery finds the display and connects automatically

- **SETUP:** Rig B or C. No remembered displays (`pm clear`) so the probe cannot short-circuit it.
  Simulator running in its default RTSP mode, or a real MFD powered on.
- **STEPS:**
  ```bash
  "$ADB" shell pm clear $PKG
  "$ADB" logcat -c
  "$ADB" shell am start -n $ACT
  sleep 12
  "$ADB" exec-out screencap -p > 04_1_auto.png
  tail -5 emulator/emu.log
  ```
- **EXPECTED:** The ring reads **Scanning**, the display is found over mDNS, and the app connects
  **by itself** — no tap. It lands on the remote screen showing `Connected · <host>`.
- **VERIFY:** `04_1_auto.png` shows the remote screen. `emu.log` (rig B) shows `RRC client
  connected`. The app must resolve **both** `_rtsp._tcp` and `_rym_rrc._tcp` before offering an
  endpoint — a half-resolved display is not connectable.
- **PASS/FAIL:** PASS if it connects with no user action. FAIL if it finds the display but waits
  for a tap, or never finds it while another mDNS browser on the same network does (cross-check:
  `python emulator/scripts/find_mfd.py`). **BLOCKED** on rig A.

---

### 04.2 The version byte is parsed as hex from the TXT record

Getting this wrong is silent: frames are sent and simply do not take effect.

- **SETUP:** Connected via discovery (04.1) to a display advertising `raymarine-mfd-rrc-version`
  = `1.10`.
- **STEPS:** Press any key on the remote and read the simulator's decode.
  ```bash
  "$ADB" logcat -c; tap_text "Menu"; sleep 1; tail -3 emulator/emu.log
  ```
- **EXPECTED:** The frame's version byte is **0x10** (16) — characters `[2,4)` of `"1.10"` read as
  hexadecimal. Not 1, not 10.
- **VERIFY:** The simulator decodes the button frame without complaint (it validates the header).
  For raw confirmation, the frame is `45 43 52 52 01 10 01 02 00 78 01` for Menu-down.
- **PASS/FAIL:** PASS if frames decode with version `0x10`. FAIL if the version byte is `0x01` or
  `0x0A` — the parse regressed to a decimal or naive reading. **BLOCKED** on rig A.

---

### 04.3 Recents are tried before the network scan

- **SETUP:** At least two remembered displays, where the **second** is reachable and the first is
  not. Easiest construction: connect to the simulator (remembered), then add a dead one by
  connecting to it... which cannot be remembered — so instead, remember the simulator, then
  **stop** the simulator, relaunch (probe fails, scan runs), restart the simulator.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  "$ADB" shell am force-stop $PKG && "$ADB" shell am start -n $ACT
  sleep 3
  "$ADB" exec-out screencap -p > 04_3_probe.png
  ```
- **EXPECTED:** On launch the ring reads **Scanning** while the remembered displays are probed
  first; each unreachable one is abandoned after a short bounded timeout (~3 s) rather than
  hanging; then the network scan runs. The user sees one continuous scanning state throughout.
- **VERIFY:** With the display reachable, connection happens within a couple of seconds — much
  faster than a full mDNS sweep. With it unreachable, the app moves on rather than stalling
  indefinitely.
- **PASS/FAIL:** PASS if a reachable remembered display connects quickly and an unreachable list
  does not hang startup. FAIL if launch blocks for tens of seconds on dead addresses.

---

### 04.4 Every remembered display is tried, not just the most recent

- **SETUP:** Two remembered displays. Make the **most recent one unreachable** and an older one
  reachable. On rig A this can be simulated by connecting to the simulator on one port, then
  reconfiguring the simulator to a second port and connecting again, then reverting.
- **STEPS:**
  ```bash
  "$ADB" shell am force-stop $PKG && "$ADB" shell am start -n $ACT
  sleep 10
  "$ADB" exec-out screencap -p > 04_4_second.png
  ```
- **EXPECTED:** The app tries them in order, skips the dead one, and connects to the reachable
  older entry. A boat with a helm and a cabin unit reconnects to whichever is actually powered.
- **VERIFY:** The status line names the **reachable** display, not the most-recent dead one.
- **PASS/FAIL:** PASS if a non-first remembered display is reached. FAIL if only the first entry is
  ever attempted.

---

### 04.5 No display found — the screen does not dead-end

- **SETUP:** Rig A, or any rig with the simulator stopped and no MFD present. `pm clear` first so
  no recents exist.
- **STEPS:**
  ```bash
  "$ADB" shell pm clear $PKG
  "$ADB" shell am start -n $ACT
  sleep 20                     # past the scan window
  "$ADB" exec-out screencap -p > 04_5_notfound.png
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -iE 'text="(No display found|Scan again|Manual connect)"'
  ```
- **EXPECTED:** The ring stops and reads **No display found**, with a plain-language note that some
  boat networks block automatic discovery, a **Scan again** button, and **Manual connect** still
  available. It never spins forever, and it is never a modal that quits the app on an outside tap.
- **VERIFY:** All three strings present in the dump.
- **PASS/FAIL:** PASS if the failure state offers both retry and manual entry with an explanation.
  FAIL if the spinner runs indefinitely or the only option is retry.

---

### 04.6 Scan again restarts the search

- **SETUP:** 04.5 (idle, nothing found). Now make a display reachable.
- **STEPS:**
  ```bash
  # start the simulator now
  tap_text "Scan again"
  sleep 12
  "$ADB" exec-out screencap -p > 04_6_rescan.png
  ```
- **EXPECTED:** Scanning resumes and the now-present display is found and connected to.
- **VERIFY:** Screenshot shows the remote screen.
- **PASS/FAIL:** PASS if a re-scan finds a display that appeared after the first sweep. FAIL if
  Scan again does nothing. **BLOCKED** for the discovery half on rig A (the recents half still
  applies).

---

### 04.7 A multicast lock is held while scanning

Many Wi-Fi drivers filter multicast unless a lock is held, and mDNS *is* multicast — without it
discovery fails silently on exactly the hardware where it matters.

- **SETUP:** Rig B or C, scanning in progress.
- **STEPS:**
  ```bash
  "$ADB" shell dumpsys wifi | grep -iA5 -i "multicast"
  "$ADB" shell dumpsys package $PKG | grep -i CHANGE_WIFI_MULTICAST_STATE
  ```
- **EXPECTED:** The permission is declared, and a multicast lock is held **while scanning** and
  released when scanning stops (it costs battery, so it must not be held permanently).
- **VERIFY:** The permission grep matches. The lock appears in the wifi dump during a scan and is
  gone once connected.
- **PASS/FAIL:** PASS if the lock is held during scanning and released after. FAIL if it is never
  taken (discovery will be unreliable on real hardware) or never released.

---

### 04.8 Auto-connect does not fight an explicit disconnect

- **SETUP:** Connected, with the display still reachable.
- **STEPS:**
  ```bash
  tap_text "Disconnect"
  sleep 8
  "$ADB" exec-out screencap -p > 04_8_stays.png
  ```
- **EXPECTED:** The app stays on the connect screen showing **Disconnected**. It must **not**
  immediately rediscover and reconnect to the display it was just disconnected from.
- **VERIFY:** After 8 seconds the screenshot still shows the connect screen, not the remote.
- **PASS/FAIL:** PASS if the disconnect sticks. FAIL if the app reconnects on its own — that makes
  the Disconnect button look broken. (This was a real defect; auto-connect is suppressed until the
  user taps a recent, Scan again, or Manual connect.)
