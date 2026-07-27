# 15 — Review-council fixes

Verifies the defects found by the deep code review, plus the palette control that came out
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

### 15.4 The palette is reachable without leaving the session

- **SETUP:** Connected, or in simulation.
- **EXPECTED:** A **Screen brightness** button sits in the status bar, at the **left** end of the
  action group — the full width of the group away from Disconnect, so a reach for it in the dark
  cannot land on the control that ends the session. Each tap advances high contrast → dark → night → high contrast.
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
- **VERIFY:** Screenshot in high contrast and in night; the chart itself must differ in brightness, not only
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

### 15.7 A fresh install comes up in dusk

- **SETUP:** A fresh install (`adb uninstall` first, or `adb shell pm clear dev.openhelm.app`).
- **STEPS:** Launch with the phone's own theme set each way in turn:
  ```bash
  "$ADB" shell cmd uimode night no  && "$ADB" shell am force-stop dev.openhelm.app
  # launch, observe, then:
  "$ADB" shell cmd uimode night yes && "$ADB" shell am force-stop dev.openhelm.app
  ```
- **EXPECTED:** **Dark**, both times. The phone's light/dark setting describes a living room rather
  than a cockpit, and it is wrong in both directions here — a phone in light mode would land in high
  contrast, which glares below decks, and high contrast is the palette least likely to be right at
  the moment the app is first opened. Dark is legible in the conditions the other two are for.
- **ALSO:** An install upgraded from a build that stored `DAY` or `DUSK` must come back up in high
  contrast and dark respectively, not fall back to the default — the enum constants were renamed and
  the name is what is persisted.
- **PASS/FAIL:** PASS if a fresh install is in dusk regardless of the system setting.

---

### 15.8 The screen stays awake in keypad mode, not only with video

- **SETUP:** Connected, switched to **Remote** so the full-screen keypad is showing.
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
  Connected or simulating, switched to **Remote**.
- **EXPECTED:** The dial and every named key are fully visible. They shrink to fit rather than
  being cropped, and no key is below 56dp. Where even the minimum cannot fit, the keypad scrolls.
- **VERIFY:** Count the named keys in a screenshot — all seven must be there, with **Back** on a
  full-width row of its own exactly as on the side panel, plus the dial.
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
- **ALSO:** The panel runs the **full height of the window**, from the top edge to the bottom. The
  status bar sits over the picture rather than across the whole width, precisely so the panel keeps
  that height — the ~64dp it used to give up came straight out of every key and the dial. At
  891 × 411dp all five rows now fit without scrolling, where they previously did not. The scroll
  remains for windows too small even for the compact band.
- **PASS/FAIL:** PASS if the order is as above and nothing overlaps. FAIL on a different order, or
  on a control that cannot be reached even by scrolling.

---

### 15.15 Every palette colours its own buttons

The original defect: none of the three schemes defined `secondaryContainer`, so Material filled it
from its **baseline purple**. *Done*, *Back*, the mode switch and *Exit simulation* were lavender in
every palette — including night, where a bright non-red button is exactly what the mode exists to
remove.

- **SETUP:** Settings, in each of the three palettes in turn.
- **EXPECTED:** *Done* is blue-ish in day, blue in dusk, and **red** in night. No button anywhere
  is purple. The destructive *Disconnect* stays distinguishable in all three without going bright.
- **VERIFY:** Screenshot Settings and the remote status bar in each palette.
- **PASS/FAIL:** PASS if no control ignores the palette. FAIL on any lavender.

---

### 15.16 Settings names the palettes; the status bar cycles them

- **EXPECTED:** Settings shows **High contrast / Dark / Night** as three labelled options with a line saying
  what each is for, the current one marked by a **border** rather than a bright fill — a filled
  selection made the chosen chip the brightest thing on a night screen, which defeats the point of
  the control. Both surfaces write the same persisted value.
- **VERIFY:** Choose Night in Settings, leave, and confirm the status-bar button shows the moon.
  Cycle from the status bar, return to Settings, and confirm the marked option followed.
- **PASS/FAIL:** PASS if the two stay in step in both directions.

---

### 15.17 The two ways of using the remote are both named

The original defect: a single button labelled *Video off* / *Video on*. A one-word toggle whose
label changes has to be read twice — "Video off" is equally readable as *the video is off* and as
*tap to turn the video off*, and the two readings are opposites. No wording fixes it, because the
problem is the control rather than the copy. It also framed one of two legitimate modes as a
feature being switched off.

- **SETUP:** Connected, or in simulation.
- **EXPECTED:** A two-segment control showing **Mirror** and **Remote** at the same time, with the
  current one marked. Mirror puts the display's picture beside the controls; Remote gives the
  full-screen keypad and stops the video pipeline. Neither is presented as a degraded mode.
- **VERIFY:**
  ```bash
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null
  "$ADB" exec-out cat /sdcard/u.xml | tr '<' '
' | grep -oE 'content-desc="(Mirror|Remote only)[^"]*"|bounds="[^"]*"'
  ```
  Both segments must be present, each ≥56dp tall — Material's own segmented buttons default to
  40dp, under even the platform minimum.
- **ALSO:** Back from Remote returns to Mirror before it offers to disconnect, as it always did.
- **PASS/FAIL:** PASS if both modes are visible and named at once. FAIL if the control still
  requires inferring the current state from a verb.

---

### 15.18 High contrast is measurably high contrast

- **SETUP:** Any screen, palette set to **High contrast**.
- **EXPECTED:** Every control is a near-black slab on a white page. Body text clears **7:1** and
  large text, icons and component outlines clear **4.5:1** — WCAG AAA. Nothing anywhere relies on
  one mid-tone separating from another, because that separation is the first thing glare removes.
- **VERIFY:** `./gradlew :app:testDebugUnitTest --tests '*PaletteContrastTest*'` computes the ratio
  for every pairing the app draws, in all three palettes. On device, confirm the keypad keys, the
  dial body, the tonal buttons and the selected mode segment are all dark slabs rather than tints.
- **NOTE:** The other two palettes are held to lower floors on purpose — dark to AA, night below it.
  Night cannot meet WCAG without emitting light it exists to avoid; that trade is deliberate and
  documented where the palette is defined.
- **PASS/FAIL:** PASS if the suite is green and the on-device look matches. A failure here names the
  exact pairing and its measured ratio.

---

### 15.19 The dial is visible in every palette

The original defect: the dial's body was never filled — only its ring was stroked — so the four
direction arrows were drawn onto whatever was behind the panel. That survived two dark palettes by
luck, light arrows on a dark page, and vanished completely in high contrast, where the page is white
and so were the arrows.

- **SETUP:** Connected or simulating; step through all three palettes.
- **EXPECTED:** In each, the dial is a filled disc with four visible arrows, an outlined OK hub, a
  ringed outer band with tick marks, and the **+** and **−** rotation marks.
- **PASS/FAIL:** PASS if all of it is visible in all three. FAIL on any palette where part of the
  dial disappears into the page.

---

### 15.20 Glyphs are large and labels are small

- **SETUP:** Connected or simulating, in any palette.
- **EXPECTED:** On every key of the panel and the keypad, the icon dominates and the word sits under
  it as a small caption. The shape is what gets recognised at arm's length on a moving boat; the
  label is read while learning the panel and rarely after.
- **VERIFY:** Covered numerically by `LayoutMathTest`; on device it should be obvious at a glance.
- **PASS/FAIL:** PASS if no key reads as mostly text.

---

### 15.21 The rotary ring shows what it is doing

Rotation was silent apart from a very light tick. The thumb turning the ring covers the arc it is
on, so any feedback drawn under it is feedback nobody sees.

- **SETUP:** Connected or simulating, in any palette.
- **STEPS:** Rest a thumb on the outer ring and sweep it round, slowly, in both directions. Then
  press the ring and hold still without turning.
- **EXPECTED:**
  - The ring lights to the pressed colour on contact, and the **two sections either side of the
    nearest detent go dark** — before anything has turned. Two rather than one because a thumb
    spans more than one 20° section.
  - Turning drags that dark pair round with the finger, and the sections behind it **fade back to
    the lit colour** over about three quarters of a second. The result is a comet tail: darkest at
    the thumb, fading to nothing behind. Direction is legible from a still frame.
  - The trail is a **darkening**, never a brightening. On a night bridge extra light is the wrong
    way round, and this is the palette where the ring is doing the most work.
  - Releasing lets the tail fade out rather than clearing it instantly.
  - The hub reads **OK** throughout. There is no numeric readout and nothing flashes.
- **VERIFY:** A screenshot taken mid-sweep is the practical check — `adb shell input swipe` along
  the ring with a long duration, screenshot while it runs. A chord across the ring works; a swipe
  through the centre does not, since the angle is undefined there.
- **PASS/FAIL:** PASS if direction and movement read without moving the hand off the ring. FAIL if
  the only indication is under the finger, or if anything flashes.

---

### 15.22 Presses can be felt through a glove

- **SETUP:** Any palette, system haptics on.
- **EXPECTED:** A key, dial sector or hub going down produces a distinctly heavier effect than a
  keyboard tap — `CONFIRM` where the platform has it, `LONG_PRESS` below API 30. Ring detents stay
  lighter than a press, because they fire repeatedly through a sweep and a full press-weight thump
  on each one blurs into a buzz; they are still heavier than the `CLOCK_TICK` they used to be.
- **ALSO:** With system haptics **off**, nothing vibrates. Feedback goes through
  `performHapticFeedback`, so the user's own setting still governs; the app holds no `VIBRATE`
  permission.
- **PASS/FAIL:** PASS if a press is clearly felt and the setting is still respected. If it is still
  too light on real hardware, the next step is an explicit `VibrationEffect` amplitude, which does
  need the permission.
