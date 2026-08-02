# 19 — Failures crossed with lifecycle and user action

**Tag:** any
**Rig:** A, B or C.

Files [16](16-resilience-faults.md)–[18](18-discovery-resilience.md) fail the network while the app
sits still. Real failures do not wait for a convenient moment: the phone rotates, the user
backgrounds the app to check the weather, a finger is holding a key, someone taps Disconnect
mid-reconnect. Each of those crosses the retry machinery with a *different* piece of state, and the
crossings are where the bugs live.

The most safety-relevant case here is L3. The MFD implements key auto-repeat itself, so a key it
believes is still held **repeats forever** — the cursor runs away and the chart pans off with no
further input. Every press path guarantees UP after DOWN via `finally`, including cancellation. L3
asks what happens when the *connection* dies mid-press.

See the README's fault-harness section for `fault.py` and the constants asserted here.

---

## SETUP

```bash
cd emulator
python -m mfd_emulator --no-discovery --no-console --log-file emu.log
```

App connected and streaming in Mirror mode unless a case says otherwise.

---

## L1 — Backgrounded during a reconnect

**STEPS**

```bash
python scripts/fault.py rrc-down
sleep 3
"$ADB" shell input keyevent KEYCODE_HOME      # background mid-reconnect
sleep 20
python scripts/fault.py rrc-up
sleep 5
"$ADB" shell am start -n $ACT                 # foreground again
```

**EXPECTED**

1. Backgrounding does not crash and does not tear the session down by itself — the connection
   machinery lives in an application-scoped coroutine precisely so a screen leaving composition
   cannot drop the control channel.
2. On return the UI shows a **truthful** state: either reconnected (`Connected`), or the connect
   screen with `Connection lost` if the retry budget ran out while away. Never a stale `Connected`
   over a dead socket, and never a `Reconnecting` with no loop behind it.
3. The system bars are hidden again on return (immersive is re-applied on focus, not once).

**VERIFY**

```bash
"$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -o 'text="[^"]*"' | head -6
python scripts/fault.py log 20        # cross-check the UI claim against reality
"$ADB" exec-out screencap -p > l1.png # bars hidden?
```

**PASS** state on return matches the emulator log. **FAIL** on a crash, a lying status line, or
system bars left over the status row.

---

## L2 — Rotated during a reconnect

The activity declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`, so the
window rotates **without** the activity recreating — the connection and the retry loop should not
even notice.

**STEPS**

```bash
python scripts/fault.py close-rrc
"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0     # portrait attempt, mid-reconnect
sleep 3
"$ADB" shell settings put system user_rotation 1     # back to landscape
```

**EXPECTED**

1. The remote screen stays landscape regardless (`LockLandscape` holds `SENSOR_LANDSCAPE` while a
   session is live) — this is a mounted display, not a handheld.
2. The reconnect completes normally; the rotation does not restart it.
3. No flicker to the connect screen and no duplicate connection in the emulator log.

**VERIFY** `fault.py log 15` shows exactly **one** reconnection, not two.

**PASS** single clean reconnect, orientation held. **FAIL** on activity recreation (visible as the
session restarting) or duplicate connections.

---

## L3 — Connection dies while a key is held (runaway-key guarantee)

**STEPS**

```bash
"$ADB" logcat -c
# Hold a direction key: a long same-point swipe is a hold.
"$ADB" shell input swipe <dial UP x> <dial UP y> <same x> <same y> 4000 &
sleep 1
python scripts/fault.py close-rrc      # kill the socket mid-hold
wait
python scripts/fault.py log 20
```

**EXPECTED**

1. The DOWN was sent before the drop; the UP is emitted by the gesture's `finally` regardless.
2. Because the socket died, the UP may not reach the display — it cannot. What matters is what
   happens **next**: on reconnect the app must not resend the stale DOWN (frames queued before a
   connection opens are drained as stale input), and must not leave its own state believing a key
   is held.
3. After reconnect, a fresh press of the same key produces a clean DOWN/UP pair in the log.

**VERIFY** the log after reconnect shows no orphaned DOWN and a well-formed pair for the next
press. Inspect for any `button … down` with no matching `up` **after** the reconnect.

**PASS** no orphaned DOWN survives the reconnect; next press is clean. **FAIL** if a stale DOWN is
replayed after reconnect (on real hardware that is a runaway cursor).

> On rig C this case is worth doing by hand and *watching the display*: the failure is visible as
> the cursor continuing to travel after the phone has stopped sending.

---

## L4 — User taps Disconnect during a reconnect

An explicit Disconnect must stick. The connect screen re-mounts and would otherwise immediately
find and reconnect to the display it was just disconnected from, making the button look broken —
hence `autoConnectSuppressed`.

**STEPS**

```bash
python scripts/fault.py rrc-down
sleep 4                                  # app is in "Reconnecting"
tap_text "Disconnect"                    # then confirm in the dialog
tap_text "Disconnect"
python scripts/fault.py rrc-up
sleep 20
```

**EXPECTED**

1. The Disconnect confirmation dialog appears even mid-reconnect (the status-bar controls stay live
   while the panel is dimmed).
2. After confirming: connect screen showing `Disconnected` — **not** `Connection lost`; the user
   ended this, not the network.
3. With the display back up, the app **stays** on the connect screen. It does not auto-reconnect.
4. `Scan again` reconnects immediately when the user asks.

**VERIFY**

```bash
"$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -o 'text="[^"]*"' | grep -Ei "disconnected|connection lost|scan"
python scripts/fault.py log 20     # no connection attempts between the disconnect and "Scan again"
```

**PASS** says `Disconnected`, no unattended reconnect, manual scan works. **FAIL** if it
auto-reconnects, or if it reports `Connection lost` for a user-initiated disconnect.

---

## L5 — Cold start into a dead display

**STEPS**

```bash
python scripts/fault.py net-down
"$ADB" shell am force-stop $PKG
"$ADB" shell am start -n $ACT
```

**EXPECTED**

1. The launch probe tries the remembered display, is refused, and falls through to a scan — all
   bounded, no hang on a black screen.
2. The app settles on the connect screen offering `Scan again` and the recents button. It does
   **not** enter the remote screen at all (there was never a connection to lose), so the user is
   never shown a dimmed control panel for a session that never existed.
3. `net-up` then `Scan again` connects normally.

**VERIFY** UI dump within ~25 s of launch shows the connect screen; no `Reconnecting`.

**PASS** bounded, lands on the connect screen, recovers on demand. **FAIL** on a hang, a crash, or
a phantom remote screen.

---

## L6 — Screen-on and immersive across an outage

**STEPS** with a session live, `python scripts/fault.py net-down`; wait for the give-up.

**EXPECTED**

1. While `Reconnecting`, the screen stays awake (`KeepScreenOn` belongs to the *session*, not the
   video) — a mounted phone must not blank mid-manoeuvre because the link hiccuped.
2. Once the app gives up and returns to the connect screen, the keep-awake flag is **released** and
   the system bars come back. The app must not silently hold the display on forever after a session
   ends.

**VERIFY**

```bash
"$ADB" shell dumpsys window | grep -i "keepScreenOn\|FLAG_KEEP_SCREEN_ON"
"$ADB" exec-out screencap -p > l6.png     # bars visible on the connect screen
```

**PASS** held during the session, released after. **FAIL** if the flag persists on the connect
screen (a battery bug that only shows up as a flat phone next morning).

---

## PASS/FAIL — file summary

| ID | Requires | Status |
|---|---|---|
| L1 backgrounded mid-reconnect | A/B/C | not yet executed |
| L2 rotated mid-reconnect | A/B/C | not yet executed |
| L3 connection dies mid-hold | A/B/C (C to *see* it) | not yet executed |
| L4 Disconnect mid-reconnect | A/B/C | not yet executed |
| L5 cold start into a dead display | A/B/C | not yet executed |
| L6 screen-on / immersive across an outage | A/B/C | not yet executed |
