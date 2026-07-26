# 03 — Manual connect and the transport dropdown

Typing an address is a **first-class way in**, not a fallback: mDNS is routinely blocked or flaky on
boat Wi-Fi. It lives on its own screen so the connect screen can stay a single scanning state.

The video transport selector lives here **and only here**. UDP is what every real display serves;
the interleaved-TCP option exists solely so a simulator behind an emulator's NAT can deliver frames.

> Read [README.md](README.md) first.

**Rig:** A (address `10.0.2.2:8555:50000:RAYMARINEMFD:10`, transport TCP), B or C (bare host IP,
transport UDP).

---

### 03.1 Reaching the screen

- **SETUP:** Connect screen showing.
- **STEPS:**
  ```bash
  tap_text "Manual connect"
  sleep 2
  "$ADB" exec-out screencap -p > 03_1_manual.png
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -i 'text="'
  ```
- **EXPECTED:** A screen titled **Manual connect** with a **Back** action, a **Display address**
  field, a **Video transport** dropdown showing **UDP**, and a **Connect** button that is
  **disabled** while the field is empty.
- **VERIFY:** The dump contains `Manual connect`, `Display address`, `Video transport`, `UDP`,
  `Connect`. The Connect node reports `enabled="false"`.
- **PASS/FAIL:** PASS if all present and Connect starts disabled. FAIL if Connect is tappable with
  an empty address.

---

### 03.2 A bare IP address is enough

The user should never have to type ports or a stream path.

- **SETUP:** 03.1.
- **STEPS:**
  ```bash
  tap_text "Display address"
  "$ADB" shell input text '192.168.131.1'
  sleep 1
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -iE 'text="Connect"' -A2
  "$ADB" exec-out screencap -p > 03_2_bareip.png
  ```
- **EXPECTED:** Connect becomes **enabled**. The standard ports and path are filled in behind the
  scenes (RTSP 8554, control 50000, path `RAYMARINEMFD`, version byte `0x10`) — none of which the
  user typed or sees.
- **VERIFY:** The Connect node reports `enabled="true"`. The supporting text says the IP alone is
  enough.
- **PASS/FAIL:** PASS if a bare IP enables Connect. FAIL if the full colon-separated form is
  required.

---

### 03.3 The compact five-field form still works

Needed for unusual setups and for the simulator, whose ports differ from a real display's.

- **SETUP:** 03.1, field cleared.
- **STEPS:**
  ```bash
  tap_text "Display address"
  "$ADB" shell input text '10.0.2.2:8555:50000:RAYMARINEMFD:10'
  sleep 1
  "$ADB" exec-out screencap -p > 03_3_compact.png
  ```
- **EXPECTED:** Accepted; Connect enabled. Fields are `host:rtspPort:rrcPort:path:versionHex`. The
  version is **hex** — `10` means `0x10` (16), matching what a real unit advertises as `"1.10"`.
- **VERIFY:** Connect is enabled and the entered text is shown verbatim.
- **PASS/FAIL:** PASS if the compact form parses. FAIL if it is rejected.

---

### 03.4 Malformed input is rejected, not guessed at

- **SETUP:** 03.1.
- **STEPS:** For each of `""`, `"   "`, `"1.2.3.4:notaport:50000:X"`, `"host with spaces"`, clear
  the field, type it, and read the state.
  ```bash
  for BAD in "1.2.3.4:notaport:50000:X" "host with spaces"; do
    "$ADB" shell input keyevent KEYCODE_MOVE_END
    for i in $(seq 1 45); do "$ADB" shell input keyevent 67; done   # backspace the field clear
    "$ADB" shell input text "$BAD"; sleep 1
    echo "=== $BAD ==="
    "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -iE 'text="Connect"'
  done
  ```
- **EXPECTED:** Connect stays **disabled** for every malformed value, and the field's helper text
  explains what is wanted. Nothing crashes, and no partially-parsed endpoint is offered.
- **VERIFY:** Each iteration reports `enabled="false"`.
- **PASS/FAIL:** PASS if all malformed values leave Connect disabled. FAIL if any enables it.

---

### 03.5 Transport dropdown: UDP is the default and first option

- **SETUP:** 03.1.
- **STEPS:**
  ```bash
  tap_text "UDP"          # opens the dropdown
  sleep 2
  "$ADB" exec-out screencap -p > 03_5_dropdown.png
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -iE 'text="(UDP|TCP.*)"'
  ```
- **EXPECTED:** Exactly two options, in this order: **UDP**, then **TCP (testing only)**. UDP is
  the selected value on a fresh install. The supporting text under the field explains the choice:
  UDP is *"Correct for every real display"*; TCP warns that *a real display accepts this and then
  never sends a picture*.
- **VERIFY:** Both option labels appear in the dump, UDP first. The screenshot shows the warning
  text for whichever is selected.
- **PASS/FAIL:** PASS if UDP is default and first, and the TCP entry is explicitly marked as
  testing-only. FAIL if TCP is default, unlabelled, or if the transport control appears anywhere
  outside this screen (check the connect and remote screens — it must not be there).

---

### 03.6 Selecting a transport persists across restarts

- **SETUP:** 03.5 open.
- **STEPS:**
  ```bash
  tap_text "TCP (testing only)"
  sleep 1
  "$ADB" shell am force-stop $PKG && "$ADB" shell am start -n $ACT
  sleep 4
  tap_text "Manual connect"; sleep 2
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -iE 'text="(UDP|TCP.*)"' | head -1
  ```
- **EXPECTED:** The dropdown still reads **TCP (testing only)** after a restart — a development
  phone points at the simulator across many sessions.
- **VERIFY:** The first matching value is the TCP label.
- **PASS/FAIL:** PASS if the choice survives a restart. FAIL if it resets to UDP.

> Set it back to **UDP** before any real-display testing.

---

### 03.7 Connecting from the manual screen

- **SETUP:** A reachable display. Rig A: `10.0.2.2:8555:50000:RAYMARINEMFD:10` + TCP. Rig B/C: the
  host/MFD IP + UDP.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  # enter the address for your rig, dismiss the keyboard, then:
  "$ADB" shell input keyevent 4
  tap_text "Connect"
  sleep 5
  "$ADB" exec-out screencap -p > 03_7_connected.png
  tail -3 emulator/emu.log
  ```
- **EXPECTED:** The app leaves the manual screen, connects, and shows the remote. The simulator
  logs `RRC client connected`.
- **VERIFY:** Screenshot shows `Connected · <host>`; `emu.log` shows the connection.
- **PASS/FAIL:** PASS if the manual address connects. FAIL if it stays on the manual screen or
  reports an error against a display that is definitely reachable (check with
  `python emulator/scripts/find_mfd.py`, or `nc -vz <host> 50000`).

---

### 03.8 Back leaves the manual screen without connecting

- **SETUP:** Manual screen showing, with text typed.
- **STEPS:**
  ```bash
  "$ADB" shell input keyevent 4      # system Back
  sleep 2
  "$ADB" exec-out screencap -p > 03_8_back.png
  ```
- **EXPECTED:** Returns to the connect screen. No connection is attempted. The app does **not**
  exit.
- **VERIFY:** Screenshot shows the connect screen; `topResumedActivity` still names the app.
- **PASS/FAIL:** PASS if Back returns to connect. FAIL if it exits the app or connects anyway.

---

### 03.9 A mistyped address is not remembered

Only endpoints that actually connect are persisted.

- **SETUP:** Note the current recents. Then enter an address that is valid in form but unreachable,
  e.g. `192.0.2.99` (TEST-NET-1, guaranteed dead), and tap Connect.
- **STEPS:**
  ```bash
  # after the connect attempt fails / hangs, go back to the connect screen
  "$ADB" shell input keyevent 4
  sleep 2
  "$ADB" exec-out screencap -p > 03_9_recents.png
  ```
- **EXPECTED:** `192.0.2.99` never appears as a recent button, because it never established a
  connection.
- **VERIFY:** The recent buttons are unchanged from before the attempt.
- **PASS/FAIL:** PASS if unreachable addresses are not remembered. FAIL if the recents list fills
  up with addresses that never worked.
