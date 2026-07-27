# OpenHelm design review — 2026-07-26

## Executive summary

OpenHelm's connect and settings flows are solid, restrained Material 3 work — the
scanning/timeout/manual-connect sequence shows real thought about a boat-Wi-Fi failure
mode most apps ignore. The remote-control screen does not clear the same bar. It is the
screen used at the helm, potentially near an active autopilot, and it has four
independently-corroborated **P0** defects: the phone-landscape control layout physically
overlaps itself (dial ring collides with Back/Rng buttons), the phone-portrait layout
reduces the video to a ~12–15% sliver, touch targets across the remote screen measure
roughly half Android's own 48dp minimum, and a lost connection can leave a frozen chart
on screen looking live with only a small, easy-to-miss text cue. All four were flagged
independently by two or three of the three critics using different methods (visual
inspection, usability heuristics, platform/API diagnosis), which is the strongest
evidence signal this review format produces.

Below the P0s, the review surfaced a real severity disagreement — resolved in favor of
treating the unexplained-disabled-Connect-button as a P1 task-completion failure, not a
P3 polish nit — and one open product question that materially changes scope: **is phone
a genuinely supported form factor, or is the real deployment a landscape-mounted
tablet at a nav station?** That answer changes the two highest-priority fixes from a
one-line orientation lock to a multi-week adaptive-layout rebuild, and should be settled
before anyone estimates the P0 work.

**Method:** two AVDs (Galaxy Tab S10 FE-equivalent tablet, Pixel 10-equivalent phone),
both API 35, screenshotted through every reachable screen of the app running against the
project's own MFD simulator, then reviewed by a three-seat critic council (Visual
Design, UX/Usability, Android Platform) that cross-examined its own findings before a
Judge synthesized the prioritized list below.

---

## Screenshot inventory

All captures are debug builds against `emulator/` (the project's Python MFD simulator),
not a real Raymarine display. The on-screen debug stats line (`16 fps · q0 · dec ms ·
drop · gap`), the SMPTE color-bar test pattern with a burned-in frame counter, and the
"TCP (sim)" transport tag are simulator/dev-build artifacts, not shipped UI — noted
inline where a finding depends on distinguishing the two.

### Tablet — OpenHelmTab35, 2304×1440, 264dpi, landscape, API 35

| # | File | Description |
|---|---|---|
| 1 | `01_connect_scanning.png` | Connect screen, active mDNS scan, sweeping ring indicator |
| 2 | `02_connect_timeout.png` | Connect screen after 12s scan timeout — "No display found", Scan again |
| 3 | `03_overflow_menu.png` | Kebab menu open — single item, "Manage displays" |
| 4 | `04_settings_empty.png` | Manage displays, empty state (no remembered displays) |
| 5 | `05_manual_connect_empty.png` | Manual connect form, address field empty, Connect disabled |
| 6 | `06_manual_connect_filled.png` | Manual connect, address typed, IME visible |
| 7 | `07_transport_dropdown.png` | Video-transport dropdown open — UDP / TCP (testing only) |
| 8 | `08_manual_connect_tcp.png` | TCP transport selected, helper text warns it's testing-only |
| 9 | `09_remote_connecting.png` | Remote screen, "Connecting…", video pane still black/spinner |
| 10 | `10_remote_side_by_side.png` | Remote screen connected — video left, control panel right |
| 11 | `11_remote_keypad_only.png` | Control-only mode (Video off) — centered keypad, no video pane |
| 12 | `12_remote_reconnecting_error.png` | "Reconnecting · Socket closed" over a frozen video frame |
| 13 | `13_connect_disconnected_recents.png` | Connect screen after explicit Disconnect — idle + recents pill |
| 14 | `14_settings_remembered.png` | Manage displays with one remembered entry |
| 15 | `15_settings_rename_pending.png` | Renaming a remembered display, IME open, unsaved edit |

### Phone — OpenHelmPhone35, 1280×2856, 420dpi, portrait unless noted, API 35

| # | File | Description |
|---|---|---|
| 1 | `00_splash.png` | Cold-start splash — ship's-wheel glyph only, no text |
| 2 | `01_connect_scanning.png` | Connect screen, active scan |
| 3 | `02_connect_timeout_pressed.png` | Timeout state, "Manual connect" caught mid-press (ripple) |
| 4 | `04_manual_connect_filled.png` | Manual connect, address typed |
| 5 | `05_transport_dropdown.png` | Video-transport dropdown open |
| 6 | `06_remote_connecting.png` | Remote screen, portrait, connecting |
| 7 | `07_remote_portrait.png` | Remote screen connected, portrait — video reduced to a thin band |
| 8 | `08_remote_keypad_portrait.png` | Control-only mode, portrait |
| 9 | `09_remote_keypad_landscape.png` | Control-only mode, **landscape** |
| 10 | `10_remote_landscape.png` | Remote screen connected, **landscape** — dial/button overlap |
| 11 | `11_connect_disconnected_landscape.png` | Connect screen, **landscape**, idle — Manual connect below the fold |
| 12 | `12_connect_disconnected_recents.png` | Connect screen, portrait, idle + recents pill |
| 13 | `13_overflow_menu.png` | Kebab menu open |
| 14 | `14_settings_remembered.png` | Manage displays with one remembered entry |

A true empty-state manual-connect capture and a phone rename-pending capture were not
obtained (session time budget); the tablet captures of both exist and are equivalent
UI, just at a different density.

---

## Council method

Three critics reviewed the full screenshot set independently (round 1), then each
received the other two seats' findings and was required to name and rebut or concede at
least one specific claim (round 2, cross-examination) before a Judge — who saw the full
transcript but not the screenshots — synthesized a single prioritized list. One finding
(a claimed three-way inconsistency in "Manual connect" button styling) was retracted in
round 2 after the critic re-checked the source file and found it was a Material ripple
/ pressed-state frame, not three different resting styles — a live example of the
cross-examination step catching a misread rather than laundering it into the verdict.

---

## Per-critic findings

### Visual Design

**Position:** OpenHelm has a coherent nautical dark-navy foundation and a restrained
palette, but currently reads as an engineer's functional build rather than a polished
product — phone-landscape layout collisions, two competing control vocabularies (side
panel vs. keypad), and unthemed default M3 popups are the main gaps.

- Dial ring, Back/Rng buttons overlap and clip in `phone/10_remote_landscape.png`; the
  "Video off" link sits on top of the video pane itself.
- Side-panel mode and keypad-only mode name, draw, and order the same functions
  differently: `Rng −`/`Rng +` (side panel) vs. bare `+`/`−` (keypad); `Swch` vs.
  `Pane`; Home/Menu left-right order flips between `tablet/10_remote_side_by_side.png`
  and `tablet/11_remote_keypad_only.png`.
- `tablet/12_remote_reconnecting_error.png`: the "Video: Socket closed — retrying"
  caption renders in low-contrast blue over a saturated color-bar test pattern —
  illegible — and duplicates the coral "Reconnecting · Socket closed" status line
  top-left. Neither uses an M3 error-role color.
- Overflow and dropdown menu popups (`tablet/03_overflow_menu.png`,
  `tablet/07_transport_dropdown.png`) render in a flat, unthemed gray that doesn't pull
  from the app's navy `ColorScheme` at all — confirmed in cross-examination to be a real
  theming bug (default `Surface`/`PopupMenu` path), not a one-off visual nit.
- Abbreviations are inconsistent in style: `Swch` drops vowels, `WPT` is an initialism,
  `Rng −` keeps a mathematical minus and a space, `Remote Ctrl` half-abbreviates.
- The "OpenHelm" wordmark is plain `Roboto Light` text; the ship's-wheel glyph shown on
  `phone/00_splash.png` never reappears anywhere else in the app.
- `tablet/14_settings_remembered.png`: the Manage Displays screen doesn't match the M3
  patterns used elsewhere — a bare "Done" text link instead of a top app bar, and
  "Forget" (destructive) styled brighter than the disabled "Save" beside it.
- Four subtly different navy container fills are used across side-panel buttons,
  keypad tiles, the recents pill, and settings cards, with no single defined
  `surfaceContainer` token set.
- The disabled "Connect" button in `tablet/05_manual_connect_empty.png` is nearly
  invisible against the background (flagged here as a contrast issue; see the Judge's
  ruling below — the UX seat's task-completion framing takes priority).

**Retracted:** a claimed inconsistency in "Manual connect" CTA styling across three
screens. Re-verification showed `phone/02_connect_timeout_pressed.png` is the same text
button as elsewhere, caught mid-press with its Material ripple/state-layer fill visible
— not a third resting style.

### UX/Usability

**Position:** Core flows are legible and the empty/timeout states show real thought,
but the remote screen — the screen used mid-maneuver — fails the helm context on touch
target size, cryptic labels, a broken phone-landscape layout, and disconnect/stale-video
communication too quiet for what may be a high-stakes autopilot scenario.

- `phone/10_remote_landscape.png`: overlapping hit areas on the dial ring and
  Back/Rng buttons invite mis-taps that send real commands to a display possibly
  driving an autopilot.
- Keypad and side-panel buttons measure roughly 22–32dp on tablet and 22–25dp on
  phone (estimated from pixel dimensions against known dpi) — about half Android's
  48dp minimum interactive size, and far below what's usable with wet or gloved hands.
  Independently confirmed by the Platform seat as an actual Material
  `minimumInteractiveComponentSize` conformance failure, not just an ergonomic
  preference.
- `tablet/12_remote_reconnecting_error.png`: the last good video frame stays on screen
  at full brightness through a "Reconnecting · Socket closed" state, with only small,
  low-contrast text as the loss-of-link cue. A helmsman glancing at the screen could act
  on a frozen chart believing it's live.
- Control labels (`Swch`, `WPT`, `Rng −`/`Rng +`, `Pane`, `Remote Ctrl`) are cryptic,
  inconsistent between modes, and have no tooltips or long-press hints.
- `tablet/06_manual_connect_filled.png` / `phone/04_manual_connect_filled.png`: the
  visible example address (`10.0.2.2:8555:50000:RAYMARINEMFD:10`) directly contradicts
  the helper text above it ("The display's IP address is enough") with no inline
  validation or format hint for the multi-colon syntax.
- No screen tells a first-time user what the app needs before or during the scan (same
  Wi-Fi network as the display, compatible model) — the timeout hint mentions blocked
  discovery but not the much more common failure of being on the wrong network.
- "Disconnect" sits as a small top-right text link immediately beside the
  visually-identical "Video off" toggle, with no confirmation — a mistapped glove-tap
  mid-maneuver can kill the control session.
- `phone/07_remote_portrait.png`: the video stream occupies roughly 12% of the screen
  in a tall black column with large dead space above and below.
- The single fixed dark theme, plus a small low-contrast monospace status line, will be
  hard to read in direct sunlight at a helm.
- `tablet/09_remote_connecting.png`: header reads "Connected" while the video pane
  still shows only a spinner at 0 fps — a mixed signal with no timeout or escape hatch
  offered.
- The status text "Socket closed" is raw implementation jargon surfaced directly to the
  user.
- The "TCP (testing only)" transport option — whose own helper text says a real display
  "accepts this and then never sends a picture" — is exposed in what may be a release
  build.
- The recents pill (`tablet/13_connect_disconnected_recents.png`) shows a raw IP
  address even when the display has a saved name, with no section label.
- "Forget" sits beside a disabled "Save" with no confirmation on the destructive
  action.
- The dial ring's zoom gesture is undiscoverable (no +/− markings) and duplicates the
  explicit `Rng −`/`Rng +` buttons.
- Settings/Manage-displays is reachable only via an unlabeled kebab menu on the connect
  screen, and not reachable at all once connected.

### Android Platform

**Position:** Connect and settings flows are serviceable Material 3, but the remote
screen is a single fixed-geometry landscape layout with no window-size adaptation —
producing an unusable phone-portrait experience and physically overlapping controls in
phone landscape — while the tablet's extra space and edge-to-edge/immersive
opportunities go unused.

- `phone/10_remote_landscape.png`: the fixed-dp control panel overflows a
  ~457dp-tall compact-height window; the dial ring's arc renders behind/through the
  Back and Rng buttons. Fix: gate the panel on `WindowSizeClass`
  (`heightSizeClass == Compact`) with a condensed variant, or size it with
  `BoxWithConstraints` instead of fixed dp.
- `phone/07_remote_portrait.png`: the same landscape-shaped `Row` (video weight-1 +
  fixed 180dp panel) is used unconditionally, so it renders crammed into a portrait
  window with the video letterboxed to a sliver. Fix: either lock `RemoteScreen` to
  landscape orientation, or build a genuine adaptive portrait layout (full-width video
  on top, controls stacked below) — see the open scope question below.
- `phone/11_connect_disconnected_landscape.png`: in compact-height landscape, "Manual
  connect" is pushed below the fold with no scroll affordance — confirmed by the Visual
  seat on re-read.
- `tablet/09/10`: the same 180dp phone-sized panel is reused verbatim on a 10.9"
  Expanded window, leaving roughly 250dp of empty vertical space between button groups.
  Fix: scale panel dimensions from `WindowSizeClass`.
- The root `Surface` applies `safeDrawingPadding()` globally, letterboxing the entire
  app inside system-bar insets — the video pane never draws edge-to-edge. Fix: apply
  insets per-pane (video ignores insets; the control panel and status row apply them).
- System bars remain visible throughout video playback, and the gesture-navigation pill
  overlaps the video's bottom edge in landscape — there is no immersive mode. Fix: hide
  system bars via `WindowInsetsControllerCompat` while connected, restoring them on
  disconnect.
- The app declares `enableOnBackInvokedCallback` (predictive back) but relies on an
  always-on root `BackHandler` chain, which can suppress the API 34+ back-to-home
  preview animation; the remote screen also has an on-screen "Back" button that is an
  MFD command key, not app navigation — a confusable pair with the system back gesture.
- Keypad-only mode (`tablet/11`, `phone/08`) uses a fixed-size button cluster regardless
  of available window size — raised to P1 after conceding the UX seat's touch-target
  measurement: this is a scaling gap and a Material minimum-touch-target failure
  together, not cosmetic.
- No screen uses a real `TopAppBar`; a floating kebab `IconButton` plus a row of
  `TextButton`s stand in for one everywhere.
- Text fields and cards on tablet stretch full-bleed to ~1330dp wide (e.g. the "Name"
  field in `tablet/14_settings_remembered.png` spans the entire screen) — well beyond
  M3's readable/reachable width guidance. Fix: constrain with
  `widthIn(max = 480.dp)`.
- IME insets are unverified in compact-height landscape; the captured tablet examples
  happen to clear the keyboard, but the phone-landscape case likely won't.
- The single hardcoded dark theme should be structured as a swappable `ColorScheme` now
  — day/dusk/night theming is already a stated future phase, and building the swap
  point now avoids a rewrite later.
- The absence of a `NavigationBar`/`NavigationRail` anywhere is architecturally
  correct for this app's simple three-route structure — noted for completeness, not as
  a defect.

---

## Judge's verdict — prioritized recommendations

Severity reflects corroboration across the three-seat council: items independently
raised by two or three critics via different methods (visual inspection, usability
heuristics, platform/API diagnosis) are weighted higher than single-seat observations.

### P0 — Release-blocking

| Issue | Screens | Fix | Effort | Raised by |
|---|---|---|---|---|
| Remote-panel controls physically overlap in phone landscape — dial ring collides with Back/Rng, "Video off" sits atop the video pane | `phone/10_remote_landscape.png` | Gate the panel on `WindowSizeClass` (`heightSizeClass == Compact`); condensed control variant or `BoxWithConstraints` instead of fixed dp | L | Visual, UX, Platform (all three) |
| Phone portrait: the landscape-shaped layout is used unconditionally, reducing video to a ~12–15% sliver | `phone/07_remote_portrait.png` | Lock `RemoteScreen` to landscape, **or** build a true adaptive portrait layout (video full-width top, controls stacked below) — decision depends on the open scope question below | M–L | Visual, UX, Platform (all three) |
| Remote-screen touch targets measure roughly half Android's 48dp minimum — a `minimumInteractiveComponentSize` conformance failure, not just a marine-glove nicety | `tablet/10,11`; `phone/08,09` | Raise all interactive targets to ≥56dp, including the keypad-only mode's fixed cluster | M | UX + Platform (independently corroborated) |
| A lost connection leaves the last video frame on screen at full brightness with only a small, low-contrast text cue — a frozen chart can read as live | `tablet/12_remote_reconnecting_error.png` | Persistent, high-contrast state treatment (banner/scrim) that cannot be missed and does not auto-dismiss — explicitly **not** a Snackbar, which would clear before an inattentive glance catches it | M | UX (Platform's Snackbar counter-proposal was raised and rejected under cross-examination) |

### P1 — High priority

| Issue | Screens | Fix | Effort |
|---|---|---|---|
| Disabled "Connect" gives no explanation why it won't fire — the primary action fails silently | `tablet/05,06`; `phone/04` | Inline validation with a visible reason when Connect is disabled | S |
| Inconsistent, cryptic control vocabulary between side-panel and keypad-only modes (labels, glyphs, order all differ) | `tablet/10` vs `11`; `phone/07` vs `08` | One canonical label/glyph/order set shared by both modes; add long-press hints | M |
| Manual-connect form pushed below the fold in compact-height phone landscape, no scroll | `phone/11_connect_disconnected_landscape.png` | Add `verticalScroll` / scroll indicator | S |
| Tablet remote panel reuses the phone-sized 180dp layout, wasting ~250dp of a 10.9" screen | `tablet/09,10` | Scale panel dimensions from `WindowSizeClass` | M |
| "Video off"/"Disconnect" are small, near-identical bare text links with no confirmation on the destructive one | all remote screens | Convert to properly sized M3 buttons; add a confirm step on Disconnect | S–M |
| Single fixed dark theme has no sunlight-legible variant | remote screens | Structure `ColorScheme` as swappable now, ahead of the already-planned day/dusk/night phase | L |
| No immersive mode; system bars and gesture pill overlap video; `safeDrawingPadding()` letterboxes the whole app instead of per-pane insets | remote landscape screens | Per-pane insets (video ignores insets; panel/status row apply them); hide system bars via `WindowInsetsControllerCompat` while connected | M |

### P2 — Medium priority

| Issue | Screens | Fix | Effort |
|---|---|---|---|
| Popup/dropdown surfaces render in default gray, not the app's `ColorScheme` | `tablet/03,07` | Theme the popup container color | S |
| "Connected" header shown while video is still a 0 fps spinner — mixed signal, no timeout offered | `tablet/09`; `phone/06` | Reconcile header state with actual connection state; offer cancel after a timeout | S |
| Raw "Socket closed" jargon surfaced as primary status text | `tablet/12` | User-facing copy pass | S |
| "TCP (testing only)" transport option — which its own helper text says fails on a real display — is exposed in what may be a release build | `tablet/07,08` | Hide behind a debug flag, or relabel clearly as developer-only | S |
| Recents chip shows a raw IP even when a name is saved | `tablet/13`; `phone/12` | Show the saved name; IP as secondary detail | S |
| Manage Displays breaks the M3 pattern used elsewhere (text-link Done, Forget visually stronger than disabled Save), no confirm on Forget | `tablet/14,15`; `phone/14` | Standardize to the app's M3 pattern; confirm on Forget | M |
| Dial-ring zoom gesture is undiscoverable and duplicates the explicit Rng buttons | `tablet/10` | Add a discoverability hint, or drop the redundant control | S |
| Settings reachable only via an unlabeled kebab, and not reachable while connected | `tablet/03`; `phone/13` | Add a persistent settings entry point | M |
| IME insets unverified in compact-height landscape | phone manual-connect, landscape | Add `imePadding()` and verify | S |
| Full-bleed ~1330dp-wide fields/cards on tablet | tablet connect/settings screens | Constrain with `widthIn(max = 480.dp)` | S |
| Teal monospace status line is an unaccounted-for third accent color | remote screens | Fold into a defined token set | S |

### P3 — Polish

| Issue | Screens | Fix | Effort |
|---|---|---|---|
| Splash wordmark is plain text; the wheel glyph never reappears elsewhere | `phone/00_splash.png` | Cosmetic branding pass — pair the wheel with the wordmark on the connect screen | S |
| Dial-ring tick/OK contrast is low; glyph geometry differs from the keypad's | `tablet/10,11` | Visual polish pass | S |
| Keypad `+`/`−` glyphs are a lighter weight than the bold text labels beside them; cluster isn't centered | `tablet/11`; `phone/08` | Normalize weight and centering | S |
| Four subtly different navy container fills with no defined tonal tokens | throughout | Define a `surfaceContainer` token set (feeds the P1 theming work) | M |
| Recents shown as an unlabeled pill rather than a structured list row | `tablet/13`; `phone/12` | Restructure as `ListItem`/`AssistChip` | S |

**Not a defect:** the absence of a `NavigationBar`/`NavigationRail` anywhere in the app
is architecturally correct for its three-route structure — the Platform critic raised
this only for completeness.

### Ruling on the one severity disagreement

The Visual and UX critics disagreed on the disabled "Connect" button: Visual filed it
as P3 (low contrast, a polish issue); UX filed it as P1 (the primary action fails
silently with no explanation). **Ruling: P1.** A user who fills the form, taps Connect,
and gets no response and no reason is a "visibility of system status" failure on the
app's one primary action in that screen — worse in a helm context than an unexplained
button being merely hard to see. Visual's contrast observation is real but is a symptom
of the same missing-validation-state gap, not a separate issue; it's folded into the
single P1 entry above.

### Open question — needs a product decision, not a design fix

The Platform critic raised a legitimate alternative reading in cross-examination: if
OpenHelm's real deployment is a landscape-mounted tablet at a nav station and phone is
not actually a supported form factor, both phone P0 layout failures collapse from a
multi-week adaptive-layout rebuild to a one-line orientation lock
(`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`). Nothing in the app, its docs, or this
screenshot set confirms or denies phone as a shipping target — memory notes describe
prior work explicitly deferring a "phone-sized AVD" as a follow-up, which suggests phone
support is intended but was never scoped. **This should be settled before anyone
estimates the two P0 layout items**, since it is the single input that most changes
their size.

---

## Appendix

**AVDs created:**

| Device | AVD name | Base device profile | Resolution | Density | API |
|---|---|---|---|---|---|
| Tablet | `OpenHelmTab35` | pixel_tablet | 2304×1440 | 264dpi | 35 (google_apis_playstore, x86_64) |
| Phone | `OpenHelmPhone35` | pixel_9_pro | 1280×2856 | 420dpi | 35 (google_apis_playstore, x86_64) |

Both booted headless (`-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim`) on
non-default ports (5586, 5588) to avoid colliding with AVDs already running in this
shared working tree.

**Build:** `dev.openhelm.app` debug build, `openhelm/app/build/outputs/apk/debug/app-debug.apk`,
built from the `raymarine-mfd-emulator-plan-01f91d` worktree
(branch `claude/rayremote-mediaplayer-video-kpwoho`) via `gradlew assembleDebug`
(already up to date at time of build — `BUILD SUCCESSFUL`, 43/43 tasks up-to-date).

**Backend:** the project's own MFD simulator (`emulator/`, `mfd.yaml`), already running
against a real captured Raymarine E9 video loop, RTSP on `10.0.2.2:8555`, RRC on port
`50000`, reached via the AVD's manual-connect screen with TCP-interleaved transport
(the AVD-only workaround documented in `mfd.yaml` — SLIRP cannot forward inbound UDP,
so this is a deliberate simulator-only deviation, not something the app does against a
real display).

**Date:** 2026-07-26. No phase failed; both device tours and the full council ran to
completion.
