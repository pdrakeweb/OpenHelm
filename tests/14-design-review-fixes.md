# 14 — Design-review fixes

Verifies the changes made in response to
[`../docs/design-review/design_review_2026-07-26.md`](../docs/design-review/design_review_2026-07-26.md):
the four P0 defects, the control-vocabulary unification, and the icon/button pass.

> Read [README.md](README.md) first.

**Rig:** A, B or C. Most of this is reachable through **Simulation mode**
([13](13-simulation-mode.md)) with no display at all. **Arch:** any.

---

### 14.1 P0 — the control panel does not overlap itself in compact-height landscape

The original defect: the panel used hard-coded dp, overflowed a compact-height phone-landscape
window, and the dial's ring drew straight through the Back/Range keys — overlapping hit areas on
a screen whose buttons send real commands. The SETUP below produces **411dp** of height, of which
the panel gets roughly 390dp once the status bar is taken out.

- **SETUP:** Force a compact-height landscape window. On an AVD this can be done without a second
  device:
  ```bash
  "$ADB" shell wm size 2340x1080 && "$ADB" shell wm density 420   # ≈891 × 411dp
  ```
  Then enter Simulation mode (13.2), or connect for real.
- **STEPS:**
  ```bash
  sleep 3
  "$ADB" exec-out screencap -p > 14_1_compact.png
  ```
- **EXPECTED:** The panel picks its **compact** band: smaller keys, labels dropped, icons only. No
  control overlaps another. Where everything cannot fit, the panel **scrolls** — a partially
  visible dial that can be scrolled to is correct; a dial drawn on top of the keys is not.
- **VERIFY:** In `14_1_compact.png`, trace each key's edge — none intersects the dial or another
  key. Scroll the panel and confirm the dial comes fully into view.
- **PASS/FAIL:** PASS if nothing overlaps at this window size. FAIL on any intersection.
- **Cleanup:** `"$ADB" shell wm size reset && "$ADB" shell wm density reset`

---

### 14.2 The panel uses the room it is given

The mirror-image defect: a 180dp phone-sized panel reused verbatim on a 10.9" tablet, wasting
~250dp.

- **SETUP:** A tablet-sized window (the default `SeaWhisperTab` geometry is fine), connected or in
  simulation.
- **EXPECTED:** Keys and dial are visibly larger than in 14.1 and labels are shown. Three bands
  exist — compact, medium, expanded — selected from the height actually available.
- **VERIFY:** Compare `14_1_compact.png` against a screenshot at tablet size; the keys should
  differ substantially in size, not just position.
- **PASS/FAIL:** PASS if the panel scales with the window. FAIL if it is identical at both sizes.

---

### 14.3 P0 — every remote control meets the touch minimum

- **SETUP:** Connected or simulating, at any window size.
- **STEPS:**
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  "$ADB" exec-out cat /sdcard/u.xml | tr '>' '\n' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"'
  ```
- **EXPECTED:** Every interactive node on the remote screen is at least **56dp** on both axes —
  above Material's own 48dp floor, because this is used with wet or gloved hands on a moving deck.
  Convert: `dp = px × 160 ÷ density` (`"$ADB" shell wm density`).
- **VERIFY:** Compute width and height for each control node; the smallest must be ≥56dp.
- **PASS/FAIL:** PASS if every control clears 56dp. FAIL on any that does not.

> **Known, documented exception:** the dial's four *direction sectors* clear 56dp tangentially but
> their radial band is narrower. See `MinDialSize`'s comment for why, and note that Remote-only
> presents the same dial at a much larger size. Re-measure this one on real
> hardware with gloves — it is a considered trade, not a compliance claim.

---

### 14.4 P0 — a dropped video link cannot be mistaken for live video

The original defect: the last decoded frame stayed on screen at full brightness with only a small,
low-contrast caption. A frozen chart read as a live one.

- **SETUP:** Connected with video rendering against the simulator. Then kill the video source:
  ```bash
  # stop the simulator (or its FFmpeg publisher) while the app is connected
  ```
- **STEPS:** The reconnect loop cycles `Failed → Connecting → Failed`, so a single screenshot at a
  fixed delay samples an arbitrary point in it. Take several across a full cycle and require *all*
  of them to be scrimmed — a scrim that covers only `Failed` leaves the frozen chart bright for
  most of each cycle, which is the bug this checks for.
  ```bash
  for i in 1 2 3 4 5 6; do sleep 3; "$ADB" exec-out screencap -p > 14_4_stale_$i.png; done
  ```
- **EXPECTED:** In **every** frame, a heavy scrim covers the frozen picture and a high-contrast
  **VIDEO NOT LIVE**
  banner states plainly that the picture is frozen, in words, naming the reason. It does **not**
  auto-dismiss and it is **not** a Snackbar — the condition is still true after any transient
  message would have cleared.
- **VERIFY:** Every `14_4_stale_*.png` shows the banner, with the chart behind it visibly darkened.
  Also tap the middle of the picture while the banner is up: nothing must reach the display — the
  scrim swallows touches as well as light, so a tap cannot land on a chart position that is stale.
- **PASS/FAIL:** PASS if a glance cannot mistake the screen for live video. FAIL if the frozen
  frame is still bright, or the only cue is small text.

---

### 14.5 Raw socket jargon never reaches the user

- **SETUP:** Any connection failure — an unreachable address works ([09](09-connection-lifecycle.md) 09.2).
- **EXPECTED:** Status reads e.g. *"Reconnecting · the display closed the connection"*, not
  *"Socket closed"*. An unrecognised error is passed through verbatim rather than swallowed, so a
  novel failure is still visible.
- **VERIFY:** Trigger a disconnect from the simulator side and read the status line.
- **PASS/FAIL:** PASS if common failures read as plain language. FAIL on raw exception text for a
  case listed in `friendlyReason`.

---

### 14.6 One control vocabulary across both modes

The original defect: the side panel said `Rng −`/`Rng +`, `Swch`; the keypad said bare `+`/`−`,
`Pane`; and Home/Menu swapped order between them.

- **SETUP:** Connected or simulating.
- **STEPS:** Screenshot Mirror mode, then switch to Remote for the full-screen keypad, and compare.
- **EXPECTED:** Identical icons and identical order in both, sourced from a single `MfdControl`
  table whose declaration order *is* the order. The range keys read **Zoom in** / **Zoom out**
  (what they do), not `Rng ±`. Every key carries an icon.
- **VERIFY:** The two screenshots show the same seven named controls in the same sequence:
  Home, Menu, Zoom in, Zoom out, Back, Pane, Waypoint.
- **PASS/FAIL:** PASS if the icons and the order agree. FAIL on any glyph or order difference.

> **Not a failure:** in the **compact** height band the side panel drops its text labels and shows
> icons alone (see 14.1) — deliberately, since shrinking the key instead would breach the touch
> minimum. The word is still there for a screen reader as the key's description. Compare labels
> only between two windows in the same band.

---

### 14.7 Screen actions are buttons, not bare text links

- **SETUP:** Visit the remote screen, Manual connect, and Settings.
- **EXPECTED:** *Mirror/Remote*, *Disconnect*, *Back*, *Done*, *Exit simulation* are all real controls —
  with an icon, a visible container, and a ≥56dp hit area — not bare text.
- **VERIFY:** Each is visibly a button in a screenshot; each measures ≥56dp tall in a UI dump.
- **PASS/FAIL:** PASS if none of the listed actions is a bare text link.

---

### 14.8 Disconnect and Forget confirm before destroying anything

- **SETUP:** Connected (for Disconnect); a remembered display (for Forget).
- **STEPS:** Tap **Disconnect** on the remote; tap **Forget** in Settings.
- **EXPECTED:** Each opens a dialog naming what will happen, with an explicit confirm and a way
  out. Disconnect's confirm offers *Stay connected*; Forget's offers *Keep*. Neither is
  destructive on the first tap — both used to be, and Disconnect sat a thumb-width from the video
  toggle.
- **VERIFY:** The dialog appears; dismissing it leaves the session/display intact.
- **PASS/FAIL:** PASS if both confirm. FAIL if either acts immediately.

---

### 14.9 The primary action explains itself when disabled

- **SETUP:** Manual connect, address field empty, then containing something malformed.
- **EXPECTED:** With the field empty: *"Enter the display's address to connect."* The Connect
  button is never disabled with no explanation.
- **VERIFY:** Use an input the parser actually rejects. A bare token with no colon (`nonsense`) is
  **accepted** — it is treated as a host and the default ports are applied, which is intended, so
  it is not a test of this. Something with a malformed port is rejected: `10.0.0.1:notaport`.
- **PASS/FAIL:** PASS if a disabled Connect always says why.

---

### 14.10 The dial's zoom gesture is discoverable

- **SETUP:** Connected or simulating.
- **EXPECTED:** A **+** and a **−** are marked on the dial's outer ring, showing which way rotation
  zooms. Previously the gesture had no on-screen indication at all and read as decoration.
- **VERIFY:** Both marks are visible on the ring in a screenshot.
- **PASS/FAIL:** PASS if the ring is marked. FAIL if the gesture is still unmarked.

---

### 14.11 Debug-only surfaces are absent from a release build

- **SETUP:** Build both variants.
  ```bash
  cd openhelm && ./gradlew :app:assembleDebug :app:assembleRelease
  ```
- **EXPECTED:** In **debug**, the video pane shows the stats overlay and Manual connect offers the
  transport dropdown. In **release**, neither appears — the stats line is developer
  instrumentation, and the alternative transport's only effect against a real display is to break
  video.
- **VERIFY:** Install the release APK and confirm both are gone.
- **PASS/FAIL:** PASS if debug-only surfaces are debug-only. FAIL if either ships.

---

### 14.12 System bars get out of the way during a session

- **SETUP:** Connected with video.
- **EXPECTED:** Status and navigation bars hide while the remote screen is up, and return on
  disconnect. A swipe from the edge brings them back temporarily — they are hidden, not trapped.
- **VERIFY:** Screenshot while connected shows no system bars; after disconnect they are back.
- **PASS/FAIL:** PASS if bars hide and reliably return. FAIL if they never hide, never return, or
  cannot be swiped back.

---

### 14.13 The 5:3 picture never overflows its pane

A bug found while fixing the above, worth its own check: `fillMaxSize().aspectRatio()` hands the
aspect box *exact* constraints, so in a wide, short window it computed its height from the full
width and drew **outside** its parent — over the status bar and its buttons.

- **SETUP:** Compact-height landscape (as 14.1), with video or simulation running.
- **EXPECTED:** The picture is pillarboxed (bars left and right) and stays strictly inside the
  video pane. The status bar above it is fully visible and its buttons are not covered.
- **VERIFY:** In the screenshot, no part of the picture crosses into the status bar row; both
  status-bar buttons are fully drawn.
- **PASS/FAIL:** PASS if the picture is contained at every window shape tried. FAIL if it spills.
