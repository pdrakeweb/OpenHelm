# 15 — Review-council fixes

Verifies the defects found by the deep code review, plus the day/dusk/night control that came out
of it. Most of this is about things that must **not** happen, so several checks are negative — read
the EXPECTED carefully before deciding a quiet screen is a pass.

> Read [README.md](README.md) first.

**Rig:** A, B or C. The palette and layout checks reach through **Simulation mode**
([13](13-simulation-mode.md)); the touch-forwarding checks need a real display or the simulator.
**Arch:** any.

---

### 15.1 A pinch never reaches the display as a chart touch

The original defect: the one-finger DOWN was sent the instant a finger landed, before anything
could know a second one was coming. Every real two-finger zoom therefore sent the display a
DOWN and then an UP — a tap on the chart, at whatever the first finger happened to touch. The pane
carried a comment saying a pinch must never leak, while doing exactly that.

- **SETUP:** Connected with video, and a way to watch what is sent. Either run against the
  simulator with its packet log, or `adb logcat` if a trace build is in use.
- **STEPS:** Pinch-zoom the video pane a dozen times, from different starting points, at both slow
  and fast finger-landing intervals.
- **EXPECTED:** **No** touch opcode (opcode 3) is transmitted for any of them. The local view
  zooms; the display is not told anything.
- **ALSO:** A single quick tap still works — a tap shorter than the grace window is emitted whole,
  DOWN and UP together, when the finger lifts. Tap the chart and confirm the cursor moves there.
- **ALSO:** A single-finger drag still pans the chart, starting within about a tenth of a second.
- **PASS/FAIL:** PASS if pinches send nothing and taps and drags are unaffected. FAIL on any touch
  opcode during a pinch, or on a tap that no longer registers.

---

### 15.2 A cancelled gesture always releases

- **SETUP:** Connected with video.
- **STEPS:** Press and hold one finger on the video, then — without lifting — swipe in from the
  screen edge to summon the system bars, which cancels the gesture. Repeat with a rotation.
- **EXPECTED:** An UP is sent for the in-flight touch. Nothing is left held.
- **WHY IT MATTERS:** The display auto-repeats a held input by itself. A DOWN with no matching UP
  does not simply linger — it repeats until something else closes it.
- **PASS/FAIL:** PASS if every DOWN has a matching UP. FAIL if the display keeps acting after the
  finger is gone.

---

### 15.3 The stale-video scrim blocks touches, not just light

- **SETUP:** As [14](14-design-review-fixes.md) 14.4 — connected, then kill the video source.
- **STEPS:** With the **VIDEO NOT LIVE** banner up, tap several times in the middle of the picture.
- **EXPECTED:** Nothing is sent to the display.
- **WHY IT MATTERS:** The scrim exists because a frozen chart reads as a live one. Drawing over it
  does not stop a tap reaching the forwarding layer beneath, and such a tap lands at a chart
  position the user believes is current — the exact error the scrim exists to prevent, committed
  through the scrim.
- **PASS/FAIL:** PASS if the scrim swallows touches. FAIL on any touch opcode while it is up.

---

### 15.4 Day / dusk / night is reachable without leaving the session

- **SETUP:** Connected, or in simulation.
- **EXPECTED:** A **Screen brightness** button sits in the status bar, at the **left** end of the
  action group — the full width of the group away from Disconnect, so a reach for it in the dark
  cannot land on the control that ends the session. Each tap advances day → dusk → night → day.
  The icon shows the palette **currently in effect**, not the one the next tap selects.
- **VERIFY:**
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  "$ADB" exec-out cat /sdcard/u.xml | tr '<' '\n' | grep -o 'content-desc="Screen brightness"'
  ```
  The node must be present and measure ≥56dp on both axes.
- **PASS/FAIL:** PASS if all three palettes are reachable from a live session in one tap each.

---

### 15.5 Night mode dims the video, not just the app

- **SETUP:** Simulation (or a live session), palette cycled to **night**.
- **EXPECTED:** The picture itself is visibly knocked back, not merely surrounded by a dark UI. The
  chart is the largest and brightest thing on screen, so a themed UI around a full-brightness video
  destroys night vision about as thoroughly as no night mode at all.
- **VERIFY:** Screenshot in day and in night; the colour bars must differ in brightness, not only
  the panel around them. This applies in **simulation as well as a real session** — the first
  version of the dim was applied only to the real pane, and simulation showed a fully bright chart
  under a night-mode UI.
- **PASS/FAIL:** PASS if the picture dims in both modes. FAIL if only the surrounding UI changes.

---

### 15.6 The palette choice is remembered; simulation mode is not

The two session flags are deliberately opposite, and it is worth checking they have not converged.

- **STEPS:**
  ```bash
  # choose night, then restart the app cold
  "$ADB" shell am force-stop dev.openhelm.app
  "$ADB" shell am start -n dev.openhelm.app/.MainActivity
  ```
- **EXPECTED:** It comes back up in **night**. It does **not** come back up in simulation.
- **WHY THEY DIFFER:** Night vision takes twenty minutes to build and seconds to destroy, so a boat
  that came alongside after dark must not relaunch into a white screen. A simulated session left on
  from last time, on the other hand, is something you could put to sea with.
- **PASS/FAIL:** PASS if the palette persists and simulation does not.

---

### 15.7 Before any palette has been chosen, the system setting is followed

- **SETUP:** A fresh install (`adb uninstall` first, or clear app data).
- **STEPS:**
  ```bash
  "$ADB" shell cmd uimode night no   && "$ADB" shell am force-stop dev.openhelm.app
  # launch, observe, then:
  "$ADB" shell cmd uimode night yes  && "$ADB" shell am force-stop dev.openhelm.app
  ```
- **EXPECTED:** Light system setting → **day**. Dark system setting → **dusk**, *not* night. Dark
  mode means "it is dark here"; night is the much stronger claim that red-shifted, heavily dimmed
  output is wanted, and it costs real legibility — it is only ever entered deliberately.
- **PASS/FAIL:** PASS if an untouched install follows the system and never lands in night.

---

### 15.8 The screen stays awake in keypad mode, not only with video

- **SETUP:** Connected. Turn **Video off** so the full-screen keypad is showing.
- **EXPECTED:** The screen does not blank. The keep-awake belongs to the session, not to the video
  pane — it used to be a flag on the `TextureView`, so the screen slept the moment the user
  switched to the keypad, which is precisely when a mounted phone gets no touches of its own.
- **VERIFY:**
  ```bash
  "$ADB" shell dumpsys window | grep -i "keep.*screen\|FLAG_KEEP_SCREEN_ON"
  ```
- **ALSO:** Disconnect, and confirm the flag is **cleared** — the app must not hold the display on
  after the session ends.
- **PASS/FAIL:** PASS if it holds during a session in both modes and releases after.

---

### 15.9 The keypad fits the window it is given

The original defect: a hard-coded 72dp key. Four rows plus gaps need 312dp, which a compact-height
landscape window does not have once the status bar is out, so the bottom row was clipped away —
present in the layout, invisible to the user, and still counted as laid out.

- **SETUP:**
  ```bash
  "$ADB" shell wm size 2340x1080 && "$ADB" shell wm density 420   # ≈891 × 411dp
  ```
  Connected or simulating, **Video off**.
- **EXPECTED:** Every key of both clusters is fully visible. The keys have shrunk to fit rather
  than being cropped, and no key is below 56dp. Where even the minimum cannot fit, the keypad
  scrolls.
- **VERIFY:** Count the named keys in a screenshot — all seven must be there, plus the five
  direction/OK keys.
- **PASS/FAIL:** PASS if nothing is clipped at any window size tried.
- **Cleanup:** `"$ADB" shell wm size reset && "$ADB" shell wm density reset`

---

### 15.10 The dial is visible to accessibility tooling

The dial is drawn on a `Canvas` behind a raw `pointerInput`, so to the framework it was an
unlabelled `Box`: invisible to TalkBack, and absent from a `uiautomator` dump — which is why the
touch-target sweep in 14.3 could not see the largest control on the panel.

- **STEPS:**
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  "$ADB" exec-out cat /sdcard/u.xml | tr '<' '\n' | grep -o 'content-desc="Cursor dial"'
  ```
- **EXPECTED:** The node is present. With TalkBack on, it announces as a button, activating it
  sends OK, and its custom actions offer the four directions plus zoom in and zoom out — rotating a
  ring is not a gesture an assistive user can perform at all.
- **ALSO:** Each key on the panel and keypad announces its description and can be activated.
- **PASS/FAIL:** PASS if the dial and every key are reachable without sighted gestures.

---

### 15.11 A hostile display cannot write the failure banner

- **SETUP:** The simulator, modified to close the connection with a reason string containing
  newlines — e.g. `closed\n\nVIDEO IS LIVE - safe to navigate`.
- **EXPECTED:** The banner shows it flattened to a single line, so injected text cannot pose as a
  second, app-authored sentence. Long strings are clipped rather than pushing the real message off
  screen. An unrecognised failure is still **shown** — swallowing it would hide a novel fault.
- **VERIFY:** Covered by `FriendlyReasonTest`; this is the on-device confirmation.
- **PASS/FAIL:** PASS if no server-supplied text can produce a second line in the banner.

---

### 15.12 A malicious mDNS advertisement cannot rewrite a remembered display

- **SETUP:** Advertise a service on the LAN whose TXT record contains a tab or a newline in its
  path or model field, and let the app remember it.
- **EXPECTED:** The remembered list is unchanged apart from the new entry. No existing entry's host
  or ports are altered, and no extra entries appear.
- **WHY IT MATTERS:** The records are tab-separated, one per line, and those three fields are
  copied verbatim from bytes any device on the Wi-Fi can choose. Unescaped, a single advertisement
  could point a trusted "Helm E95" at an address of the advertiser's choosing, or flood the list
  until the cap evicted the real displays.
- **VERIFY:** Covered by `RememberedDisplayCodecTest`; this is the on-device confirmation.
- **PASS/FAIL:** PASS if remembered entries are unaffected by what is advertised.

---

### 15.13 The local unit tests pass

```bash
cd openhelm && ./gradlew :app:testDebugUnitTest
```

- **EXPECTED:** All green. These cover the pure decisions behind the layout and the safety copy —
  the letterbox axis choice, keypad sizing at window shapes an emulator will not easily produce,
  dial sector mapping, failure-text sanitising, and the remembered-display codec.
- **PASS/FAIL:** PASS if the suite is green. A failure here is a real regression, not a flake —
  nothing in it touches the network, the clock, or a device.

---

### 15.14 The side panel is arranged for the hand, not sorted into a list

- **SETUP:** Connected or simulating.
- **EXPECTED:** Top to bottom: **Home | Menu**, then the **rotary dial**, then **Back** on a
  full-width row of its own, then **Zoom in | Zoom out**, then **Pane | Waypoint**. The dial sits
  in the middle where the hand rests, and Back — reached for constantly while walking a menu — is
  directly under it and double width, so it can be hit without looking.
- **VERIFY:**
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  "$ADB" exec-out cat /sdcard/u.xml | tr '<' '
'     | grep -oE 'content-desc="(Go|Open|Zoom|Switch|Place|Cursor)[^"]*"|bounds="[^"]*"'
  ```
  Read the bounds top-down. Back's width must be about twice a normal key's.
- **KNOWN, and correct:** in the **compact** height band (e.g. 891 × 411dp) the panel is taller
  than the window and the last pair sits below the fold — the panel scrolls to reach it. That is
  the designed answer to a window that cannot hold the layout, and it is preferred to shrinking the
  dial, whose OK hub is already exactly at the 56dp floor. At medium and expanded sizes everything
  is visible at once.
- **PASS/FAIL:** PASS if the order is as above and nothing overlaps. FAIL on a different order, or
  on a control that cannot be reached even by scrolling.

---

### 15.15 Every palette colours its own buttons

The original defect: none of the three schemes defined `secondaryContainer`, so Material filled it
from its **baseline purple**. *Done*, *Back*, *Video off* and *Exit simulation* were lavender in
every palette — including night, where a bright non-red button is exactly what the mode exists to
remove.

- **SETUP:** Settings, in each of the three palettes in turn.
- **EXPECTED:** *Done* is blue-ish in day, blue in dusk, and **red** in night. No button anywhere
  is purple. The destructive *Disconnect* stays distinguishable in all three without going bright.
- **VERIFY:** Screenshot Settings and the remote status bar in each palette.
- **PASS/FAIL:** PASS if no control ignores the palette. FAIL on any lavender.

---

### 15.16 Settings names the palettes; the status bar cycles them

- **EXPECTED:** Settings shows **Day / Dusk / Night** as three labelled options with a line saying
  what each is for, the current one marked by a **border** rather than a bright fill — a filled
  selection made the chosen chip the brightest thing on a night screen, which defeats the point of
  the control. Both surfaces write the same persisted value.
- **VERIFY:** Choose Night in Settings, leave, and confirm the status-bar button shows the moon.
  Cycle from the status bar, return to Settings, and confirm the marked option followed.
- **PASS/FAIL:** PASS if the two stay in step in both directions.
