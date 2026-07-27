# 02 — The connect screen

The app's front door. Its job is to get connected without being asked, so almost everything on it
is either the scanning state or a shortcut. This file tests what is on the screen and how it
behaves; the connecting itself is [04](04-discovery-and-autoconnect.md).

Design intent being verified: **scanning is the prominent element**, recent displays are **name-only
buttons** (no address, no ports), manual entry and Settings are **off this screen entirely** (both
live behind the overflow menu), and the whole thing reads as a GUI rather than a terminal.

> Read [README.md](README.md) first.

**Rig:** A, B or C. **Arch:** any — this is layout and navigation, which does not vary by
architecture. Re-run once on arm64 as part of a release pass.

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
- **EXPECTED:** Centred: the title **OpenHelm** and a ring containing the word **Scanning** with a
  sweeping arc. An overflow (kebab) affordance sits in the top-right — that is the **only** other
  control on a fresh install. **No recent buttons**, no address text, no transport control, no list,
  and **no "Manual connect" text visible on the body** — it lives inside the overflow menu, not on
  the screen itself (see 02.4).
- **VERIFY:** `02_1_fresh.png` shows the above. The UI dump contains `Scanning`, and does **not**
  contain any of `Manual connect`, `Video transport`, `Display address`, `control 50000`,
  `video 8555` — those only appear once the overflow menu (or a screen it opens) is on screen.
- **PASS/FAIL:** PASS if the screen is the scanning state and nothing else. FAIL if an address, port,
  transport selector, endpoint list, or the "Manual connect" label is visible on the body without
  opening the overflow menu first.

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

### 02.4 Overflow menu holds everything off the main screen — Manual connect and Settings

Both fiddly entry points live in exactly one place: the overflow. This test defines what "off the
main screen" means for 02.1.

- **SETUP:** Connect screen showing (any state).
- **STEPS:**
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  # the kebab is the top-right control; tap it by bounds, then dump the menu
  "$ADB" shell input tap 2497 113
  sleep 2
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -i 'text="'
  "$ADB" exec-out screencap -p > 02_4_menu.png
  ```
- **EXPECTED:** A dropdown containing exactly two items, in this order: **Manual connect** and
  **Settings**. Tapping **Manual connect** opens [03-manual-connect.md](03-manual-connect.md)'s
  screen. Tapping **Settings** opens the settings screen, which holds both the simulation toggle
  ([13](13-simulation-mode.md)) and display management ([05](05-remembered-displays.md)) — there is
  no separate "Manage displays" entry any more.
- **VERIFY:** The dump contains both `Manual connect` and `Settings`, and no other menu items.
  Tapping each lands on the screen named above.
- **PASS/FAIL:** PASS if the overflow holds exactly those two entries and both work. FAIL if either
  is reachable from the main screen body instead, if a stale "Manage displays" label remains, or if
  the menu does not open.

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
  tap_text "Disconnect"; sleep 1; tap_text "Disconnect"   # second tap confirms
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

- **SETUP:** Connect screen showing. Not landscape-locked ([10.1b](10-app-lifecycle-and-network.md)
  covers that this screen specifically is free to rotate, unlike the remote screen).
- **STEPS:**
  ```bash
  "$ADB" shell settings put system accelerometer_rotation 0
  "$ADB" shell settings put system user_rotation 0
  sleep 2; "$ADB" exec-out screencap -p > 02_7_a.png
  "$ADB" shell settings put system user_rotation 1
  sleep 2; "$ADB" exec-out screencap -p > 02_7_b.png
  "$ADB" shell settings put system accelerometer_rotation 1   # restore auto-rotate
  ```
  (Which of `0`/`1` is portrait vs. landscape depends on the device's natural orientation — on
  `SeaWhisperTab`, a landscape-native tablet image, `0` is landscape and `1` is portrait, the
  opposite of a typical phone. Compare the two screenshots to each other rather than assuming
  either value means a specific orientation.)
- **EXPECTED:** Both orientations render the same elements, nothing clipped or overlapping, and the
  app does not restart its scan from scratch or crash.
- **VERIFY:** Both screenshots are intact layouts, visibly different aspect ratios; no crash in
  logcat.
- **PASS/FAIL:** PASS if both orientations are usable. FAIL on clipped/overlapping controls or a
  crash. (Landscape is the primary orientation; portrait must merely not break.)
