# 05 — Remembered displays: persistence, naming, forgetting

On a boat you connect to the same display every time, so discovery should be the fallback rather
than the ritual. Every **successful** connection is remembered with its full endpoint, and the user
can give it a human name — which is the only thing the connect screen shows.

Why naming matters concretely: the vendor's model TXT record is abbreviated and misleading. The
reference unit advertises `raymarine-mfd-model=E9` but is actually an **e95** (the serial's part
number `E70021` is the reliable identifier). "Helm" beats a wrong model string.

> Read [README.md](README.md) first.

**Rig:** A, B or C. **Arch:** any — persistence and labelling are architecture-independent.

---

### 05.1 A successful connection is remembered

- **SETUP:** `"$ADB" shell pm clear $PKG`. A reachable display.
- **STEPS:**
  ```bash
  "$ADB" shell pm clear $PKG
  "$ADB" shell am start -n $ACT; sleep 4
  # connect by whatever route your rig allows (manual on rig A)
  # ... then return to the connect screen:
  tap_text "Disconnect"; sleep 3
  "$ADB" exec-out screencap -p > 05_1_remembered.png
  ```
- **EXPECTED:** A recent button now exists for that display. Its label is the model string if the
  display advertised one, otherwise the host.
- **VERIFY:** `05_1_remembered.png` shows one recent button.
- **PASS/FAIL:** PASS if the endpoint is remembered after a successful connect. FAIL if the connect
  screen is still empty.

---

### 05.2 Recents survive an app restart and a reinstall

- **SETUP:** 05.1.
- **STEPS:**
  ```bash
  "$ADB" shell am force-stop $PKG && "$ADB" shell am start -n $ACT; sleep 4
  "$ADB" exec-out screencap -p > 05_2_restart.png
  out=$("$ADB" install -r "$APK" 2>&1); echo "$out" | grep -q Success && echo INSTALL OK
  "$ADB" shell am force-stop $PKG && "$ADB" shell am start -n $ACT; sleep 4
  "$ADB" exec-out screencap -p > 05_2_reinstall.png
  ```
- **EXPECTED:** The recent persists across both (DataStore-backed).
- **VERIFY:** Both screenshots show the button — though note that with a reachable display the app
  may auto-connect past the connect screen, which also demonstrates persistence.
- **PASS/FAIL:** PASS if recents survive. FAIL if they are lost on restart.

---

### 05.3 Naming a display

- **SETUP:** At least one remembered display; connect screen showing.
- **STEPS:**
  ```bash
  "$ADB" shell input tap 2497 113          # overflow menu
  sleep 1
  tap_text "Settings"
  sleep 2
  "$ADB" exec-out screencap -p > 05_3_manage.png
  tap_text "Name"                           # focus the name field
  "$ADB" shell input text 'Helm'
  "$ADB" shell input keyevent 4             # dismiss keyboard
  sleep 1
  tap_text "Save"
  sleep 1
  "$ADB" exec-out screencap -p > 05_3_saved.png
  ```
- **EXPECTED:** Settings opens with a **Simulation mode** section at the top ([13](13-simulation-mode.md))
  and a **Displays** section below it listing each remembered display with an editable **Name**
  field, the endpoint detail beneath it (host, model, serial), and **Save** / **Forget**. Save is
  disabled until the name is changed, and disabled again once committed.
- **VERIFY:** `05_3_saved.png` shows the name in the field and **Save** greyed out (not dirty).
- **PASS/FAIL:** PASS if the name saves and Save correctly reflects dirty state. FAIL if Save stays
  enabled after saving, or the name does not persist.

---

### 05.4 The name is what the connect screen shows

- **SETUP:** 05.3.
- **STEPS:**
  ```bash
  tap_text "Done"
  sleep 2
  "$ADB" exec-out screencap -p > 05_4_label.png
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -i 'text="Helm"'
  ```
- **EXPECTED:** The recent button reads **Helm** — the name only. The address, ports and serial are
  **not** on the connect screen.
- **VERIFY:** The dump matches `text="Helm"`, and no node on that screen contains the host address.
- **PASS/FAIL:** PASS if the button shows the user's name alone. FAIL if it shows the address, or
  still shows the model string after naming.

---

### 05.5 Label falls back sensibly when unnamed

- **SETUP:** A remembered display with **no** user name.
- **STEPS:** Inspect its recent button.
- **EXPECTED:** Precedence is **user name → model TXT → host**. A display advertising `E9` with no
  user name shows `E9`; a manually-entered endpoint with no model shows its host.
- **VERIFY:** Compare the button text against what the display advertises (`find_mfd.py` on rig
  B/C, or Settings → Displays' detail line).
- **PASS/FAIL:** PASS if the fallback chain holds. FAIL if an unnamed display shows a blank button.

---

### 05.6 The full endpoint is preserved behind the label

The label is cosmetic; the connection must still use the complete endpoint.

- **SETUP:** A named display, e.g. "Helm", whose real address differs from its name in every way.
- **STEPS:**
  ```bash
  "$ADB" logcat -c
  tap_text "Helm"
  sleep 4
  tail -3 emulator/emu.log
  "$ADB" exec-out screencap -p > 05_6_connected.png
  ```
- **EXPECTED:** Connects to the correct host and control port; the remote's status line shows the
  real host address. Settings → Displays' detail line still shows host / model / serial.
- **VERIFY:** `emu.log` shows the connection; the status line names the right host.
- **PASS/FAIL:** PASS if the named button connects to the right endpoint. FAIL if naming corrupts
  or truncates the stored endpoint.

---

### 05.7 Renaming and clearing a name

- **SETUP:** A named display.
- **STEPS:** Open Settings, change the name to `Cockpit`, Save. Then clear the field
  entirely and Save again.
- **EXPECTED:** The button updates to `Cockpit`. Clearing the name reverts the label to the model
  or host fallback (a blank name is stored as "no name", not as an empty label).
- **VERIFY:** The connect screen button text follows each change.
- **PASS/FAIL:** PASS if rename and clear both work. FAIL if a blank name produces an empty button.

---

### 05.8 Forgetting a display

- **SETUP:** At least one remembered display.
- **STEPS:**
  ```bash
  "$ADB" shell input tap 2497 113; sleep 1; tap_text "Settings"; sleep 2
  tap_text "Forget"
  sleep 1
  "$ADB" exec-out screencap -p > 05_8_forgotten.png
  tap_text "Done"; sleep 2
  "$ADB" exec-out screencap -p > 05_8_connect.png
  ```
- **EXPECTED:** The entry disappears from the Displays list and its button disappears from the
  connect screen. If it was the last one, Settings shows the empty-state text for Displays.
- **VERIFY:** Neither screenshot shows the forgotten display.
- **PASS/FAIL:** PASS if forget removes it everywhere. FAIL if it reappears (unless you reconnect
  to it, which legitimately re-remembers it).

---

### 05.9 Ordering is most-recent-first, deduplicated by host

- **SETUP:** Two or more reachable endpoints (rig A: the simulator on two different configured
  ports, connected to in turn).
- **STEPS:** Connect to A, disconnect, connect to B, disconnect, then look at the connect screen.
  Then reconnect to A and look again.
- **EXPECTED:** The most recently connected display is leftmost. Reconnecting to A moves A back to
  the front rather than adding a duplicate. A previously-set name survives the reordering.
- **VERIFY:** Button order changes as described; no host appears twice.
- **PASS/FAIL:** PASS if ordering is most-recent-first with no duplicates and names preserved.
  FAIL on duplicate entries for one host, or a lost name after reconnecting.

---

### 05.10 The list is capped

- **SETUP:** Connect to more than four distinct endpoints if your rig allows; otherwise inspect the
  cap in `EndpointStore` and treat the on-device half as BLOCKED.
- **EXPECTED:** The connect screen shows at most **four** buttons. The store itself keeps a slightly
  longer history (12) so Settings → Displays can still show and clean up older entries.
- **VERIFY:** Count buttons on the connect screen; count rows in Settings → Displays.
- **PASS/FAIL:** PASS if the connect screen caps at four. FAIL if it grows without bound and pushes
  the scanning state off-screen.

---

### 05.11 Settings is reachable only via the overflow menu

- **SETUP:** Any state.
- **EXPECTED:** No "Settings" affordance on the connect screen body, the manual screen, or the
  remote screen — only inside the kebab menu, alongside "Manual connect" (02.4).
- **VERIFY:** UI-dump each screen and grep for `Settings`; it must appear only after opening the
  overflow.
- **PASS/FAIL:** PASS if management is behind the menu. FAIL if it clutters the main screen.
