# 02 — The connect screen

The app's front door. Its job is to get connected without being asked, so almost everything on it
is either the scanning state or a shortcut. This file tests what is on the screen and how it
behaves; the connecting itself is [04](04-discovery-and-autoconnect.md).

Design intent being verified: **scanning is the prominent element**, recent displays are **name-only
buttons** (no address, no ports), manual entry and display management are **off this screen**, and
the whole thing reads as a GUI rather than a terminal.

> Read [README.md](README.md) first.

**Rig:** A, B or C.

---

### 02.1 Fresh install shows scanning and nothing else

- **SETUP:** Rig A/B/C. `"$ADB" shell pm clear $PKG` so there are no remembered displays.
- **STEPS:**
  ```bash
  "$ADB" shell pm clear $PKG
  "$ADB" shell am start -n $ACT
  sleep 4
  "$ADB" exec-out screencap -p > 02_1_fresh.png
  "$ADB" exec-out uiautomator dump /dev/tty
  ```
- **EXPECTED:** Centred: the title **OpenHelm**, a ring containing the word **Scanning** with a
  sweeping arc, and a **Manual connect** text button below. An overflow (kebab) affordance sits in
  the top-right. **No recent buttons**, no address text, no transport control, no list.
- **VERIFY:** `02_1_fresh.png` shows the above. The UI dump contains `Scanning` and
  `Manual connect`, and does **not** contain any of `Video transport`, `Display address`, `control
  50000`, `video 8555`.
- **PASS/FAIL:** PASS if the screen is the scanning state plus Manual connect only. FAIL if an
  address, port, transport selector or endpoint list appears here.

---

### 02.2 Recent displays render as name-only buttons, max four

- **SETUP:** At least one successful connection has been made (see
  [05](05-remembered-displays.md) 05.1). Ideally name it first (05.3).
- **STEPS:**
  ```bash
  "$ADB" shell am force-stop $PKG && "$ADB" shell am start -n $ACT
  sleep 4
  "$ADB" exec-out screencap -p > 02_2_recents.png
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -i 'text="'
  ```
- **EXPECTED:** Each remembered display appears as one button whose text is its **label only** —
  the user-given name if set, otherwise the model string (e.g. `E9`), otherwise the host. **No
  address, port or serial appears on the button.** At most **four** buttons are shown even if more
  are remembered.
- **VERIFY:** In the dump, the recent button's `text=` is exactly the label. No node on this screen
  carries `control ` / `video ` / `S/N`. If more than four displays are remembered, count the
  buttons: exactly four.
- **PASS/FAIL:** PASS if buttons show names only and are capped at four. FAIL if an address is
  rendered on the connect screen, or if a fifth button appears.

---

### 02.3 Tapping a recent button connects to that display

- **SETUP:** 02.2, with the display reachable.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  tap_text "Helm"          # or whatever the button's label is
  sleep 4
  "$ADB" exec-out screencap -p > 02_3_connected.png
  tail -5 emulator/emu.log
  ```
- **EXPECTED:** The app connects to that endpoint and moves to the remote screen. The simulator
  logs a new `RRC client connected`.
- **VERIFY:** `02_3_connected.png` shows the remote (status line reading `Connected · <host>` and
  the control panel). `emu.log` shows the client connection.
- **PASS/FAIL:** PASS if the tapped display connects. FAIL if it connects to a different endpoint
  or does nothing. **BLOCKED** if no display is reachable.

---

### 02.4 Overflow menu reaches display management, and nothing else lives there

- **SETUP:** At least one remembered display.
- **STEPS:**
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  # the kebab is the top-right control; tap it by bounds, then dump the menu
  "$ADB" shell input tap 2497 113
  sleep 2
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -i 'text="'
  "$ADB" exec-out screencap -p > 02_4_menu.png
  ```
- **EXPECTED:** A dropdown containing **Manage displays**.
- **VERIFY:** The dump contains `Manage displays`. Tapping it opens the management screen
  ([05](05-remembered-displays.md)).
- **PASS/FAIL:** PASS if the overflow opens and offers display management. FAIL if display
  management is reachable only from the main screen body, or if the menu does not open.

---

### 02.5 Scanning is the visually dominant element

A judgement call, but a specified one: the scanning state must be what the eye lands on.

- **SETUP:** 02.1.
- **STEPS:** Look at `02_1_fresh.png`.
- **EXPECTED:** The scanning ring is large (roughly a third of the screen height), centred, and the
  only animated element. The title is lighter-weight than the scanning word. Recent buttons, when
  present, sit below it and are visually quieter. Nothing is monospaced; no raw addresses,
  hex, or port numbers are on screen.
- **VERIFY:** By eye against the screenshot.
- **PASS/FAIL:** PASS if scanning dominates and no terminal-style text appears. FAIL if the screen
  reads as a data dump or the scanning state is a small spinner in a corner.

---

### 02.6 "Scan again" appears whenever scanning is not running

- **SETUP:** Connected, then disconnect (see [09](09-connection-lifecycle.md) 09.1); or let the
  scan window elapse with no display present.
- **STEPS:**
  ```bash
  # from the remote screen
  tap_text "Disconnect"
  sleep 3
  "$ADB" exec-out screencap -p > 02_6_idle.png
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -iE 'text="(Scan again|Disconnected|No display found)"'
  ```
- **EXPECTED:** The ring reads **Disconnected** (after an explicit disconnect) or **No display
  found** (after a fruitless scan), and a **Scan again** button is present. The state is never a
  spinner that runs forever, and never a dead end.
- **VERIFY:** The dump contains `Scan again` plus one of the two state words.
- **PASS/FAIL:** PASS if an idle state always offers Scan again. FAIL if the ring keeps animating
  with nothing happening, or there is no way to restart scanning.

---

### 02.7 Layout survives rotation

- **SETUP:** Connect screen showing.
- **STEPS:**
  ```bash
  "$ADB" shell settings put system accelerometer_rotation 0
  "$ADB" shell settings put system user_rotation 0     # portrait
  sleep 2; "$ADB" exec-out screencap -p > 02_7_portrait.png
  "$ADB" shell settings put system user_rotation 1     # landscape
  sleep 2; "$ADB" exec-out screencap -p > 02_7_landscape.png
  ```
- **EXPECTED:** Both orientations render the same elements, nothing clipped or overlapping, and the
  app does not restart its scan from scratch or crash.
- **VERIFY:** Both screenshots are intact layouts; no crash in logcat.
- **PASS/FAIL:** PASS if both orientations are usable. FAIL on clipped/overlapping controls or a
  crash. (Landscape is the primary orientation; portrait must merely not break.)
