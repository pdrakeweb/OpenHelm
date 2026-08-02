# 17 — Video pipeline resilience

**Tag:** any (the *behaviour* is architecture-independent; the hardware decoder's own error paths
are **arm64** and are called out where they apply)
**Rig:** A, B or C. On rig A use the debug-only TCP transport, or nothing renders and every
expectation below collapses into "no video either way".

Video failure is the failure the app is *most* likely to meet and the one it must survive most
gracefully, because losing video does **not** end a session — it degrades it to remote-only, which
is a first-class mode. This file covers the video half in isolation; the control half is
[16](16-resilience-faults.md), and the two together are 16 S5.

The safety rule under all of it: **a picture that is not live must never look live.** The last good
frame stays on screen — during a two-second dropout it is still the most useful thing on the pane —
but under a scrim and a banner saying, in words, that it is delayed.

See the README's fault-harness section for `fault.py` and the constants asserted here.

---

## SETUP

```bash
cd emulator
python -m mfd_emulator --no-discovery --no-console --log-file emu.log
```

App connected and streaming, in **Mirror** mode. Confirm the debug overlay shows a non-zero
`fps` before starting — several expectations below distinguish "frozen" from "never started", and
that distinction is meaningless if video never ran.

---

## V1 — Stream starves (`stream-drop`): server answers, media stops

The publisher dies but the RTSP endpoint stays up. This is the *quiet* failure — nothing is
refused, packets simply stop.

**STEPS**

```bash
python scripts/fault.py stream-drop
```

**EXPECTED**

1. Video freezes on its last decoded frame immediately.
2. Within ~10 s (`RTP_STALL_TIMEOUT_MS`) the stall is detected and the overlay appears:
   `VIDEO DELAYED — NOT LIVE`, with the reason and `Reconnecting…`.
3. **The frozen frame stays visible** through the scrim — legible, obviously not live.
4. The status line still reads `Connected` and the control panel is **not** dimmed.
5. Keys keep working throughout.

**VERIFY**

```bash
"$ADB" exec-out screencap -p > v1.png          # frame visible under the banner
"$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -o 'text="[^"]*"' | grep -i "delayed\|connected"
"$ADB" shell input tap <Menu key>              # then:
python scripts/fault.py log 5                  # must show button MENU down/up
```

**PASS** overlay appeared within ~10 s, frame still visible, session still `Connected`, keys still
logged frames. **FAIL** if the pane goes black (the frame was thrown away), if the banner does not
appear (a stale chart passing as live — the one unacceptable outcome), or if the controls dim.

---

## V2 — Stream returns (`stream-resume`)

**STEPS**

```bash
python scripts/fault.py stream-resume
```

**EXPECTED** video resumes with **no user action**, the overlay clears completely, `fps` returns to
~15. Recovery within one retry backoff (3 s × attempt, capped 15 s) plus RTSP renegotiation.

**VERIFY** screenshot shows no banner and a moving chart; `fps` non-zero in the debug overlay.

**PASS** recovers unattended. **FAIL** if it needs a tap, a mode toggle, or a reconnect.

---

## V3 — Video endpoint refused (`video-down`): control must be untouched

Distinct from V1 and the distinction matters: here the RTSP **connection** fails rather than the
session starving, so it exercises the connect/DESCRIBE error path rather than the stall timeout.

**STEPS**

```bash
python scripts/fault.py video-down
```

**EXPECTED**

1. Overlay appears — faster than V1, since a refused connect does not wait out the 10 s stall.
2. Status line stays `Connected`; panel stays bright; keys keep working.
3. The app keeps retrying video **indefinitely** (unlike control, which gives up). Leave it 2–3
   minutes: it must still be retrying, still `Connected`, and must **not** have returned to the
   connect screen.
4. `video-up` → video returns unattended.

**VERIFY**

```bash
python scripts/fault.py video-down
sleep 150
"$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -o 'text="[^"]*"' | grep -i "connected\|scan again"
```

**PASS** still `Connected` after minutes of failure, never gave up, recovered on `video-up`.
**FAIL** if the session ends, if the app returns to the connect screen, or if retries stop.

---

## V4 — Video fails before the first frame ever arrives

The `everStreamed` latch distinguishes *loading* from *stale*, and the two want opposite
treatments: before any frame there is nothing behind the pane but black, so a spinner is honest;
after one, the scrim is mandatory.

**STEPS**

1. Disconnect. `python scripts/fault.py video-down`.
2. Reconnect (recents button). Control connects; video cannot.

**EXPECTED** the pane shows a **spinner**, not the stale-video banner — there is no frozen chart to
warn about, and a "NOT LIVE" banner over a black pane is a false alarm that teaches users to ignore
the real one.

**VERIFY** screenshot: spinner present, no `VIDEO DELAYED` text in the UI dump.

**PASS** spinner only. **FAIL** if the stale banner appears over a pane that never had a picture.

---

## V5 — Remote-only mode is a genuine refuge

The mode exists precisely for "video is the broken half".

**STEPS**

1. With video failing (`video-down` still active), tap **Remote**.
2. Wait 30 s. Tap **Mirror** again.
3. `python scripts/fault.py video-up`, wait for recovery.

**EXPECTED**

1. Remote-only shows the full-screen keypad with **no** overlay and **no** spinner — nothing about
   a dead video pipeline should intrude on a mode that does not use it.
2. Keys work normally in remote-only while video is down.
3. Returning to Mirror re-arms video (surface re-enters composition → pipeline restarts).
4. After `video-up`, video appears without further action.

**VERIFY** screenshots at each step; `fault.py log` shows key frames arriving during step 2.

**PASS** all four. **FAIL** if remote-only shows video error UI, or if returning to Mirror leaves a
permanently dead pane.

---

## V6 — Repeated video flapping (10 cycles)

A single failure proves the path; repetition proves nothing **accumulates** — the failure mode this
guards is a sockets/threads/codec leak that only shows after the twentieth reconnect on a boat.

**STEPS**

```bash
for i in $(seq 1 10); do
  python scripts/fault.py stream-drop;  sleep 12
  python scripts/fault.py stream-resume; sleep 15
done
```

**EXPECTED** every cycle recovers; the tenth looks like the first. No growth in the `drop` counter's
*rate*, no permanently-stuck overlay, no crash.

**VERIFY**

```bash
"$ADB" logcat -d AndroidRuntime:E *:F | tail -20        # expect nothing
"$ADB" shell ps -A -o PID,ETIME,NAME | grep openhelm    # same PID as at the start
"$ADB" shell dumpsys meminfo dev.openhelm.app | head -20
```

Compare `meminfo` before and after: a modest rise is normal, a monotonic climb of tens of MB is a
leak. Codec instances are the thing most likely to leak here — one `H264Decoder.stop()` missed per
cycle would show as native/graphics growth.

**PASS** 10/10 recovered, same PID, no unbounded memory growth. **FAIL** on any stuck cycle, crash,
or clear leak trend.

---

## V7 — Decoder error path — **arm64 / device**

`MediaCodec` errors (`ERROR_RECLAIMED` when the app loses foreground priority to another media app,
or a vendor decoder fault) cannot be injected from the simulator: they originate inside the
platform. The fix under test is that a decoder error now **fails the pipeline** rather than being
swallowed — before it, RTP kept feeding a dead codec while the UI still said `Streaming` over a
frozen chart, with no warning at all.

**STEPS** (real device) connect and stream, then force codec reclaim: start a second video app
(any streaming player) in the foreground, leave it 10 s, return to OpenHelm.

**EXPECTED** OpenHelm either keeps streaming or shows the stale-video overlay and recovers — it must
**never** show a frozen frame with no banner.

**VERIFY** `adb logcat | grep -i "codec\|OpenHelm"` around the switch; screenshot on return.

**PASS/BLOCKED** — **BLOCKED** on rig A: not injectable.

---

## PASS/FAIL — file summary

| ID | Requires | Status |
|---|---|---|
| V1 stream starves | A/B/C | **PASS** — verified 2026-07-28 (recorded in [16](16-resilience-faults.md) S4) |
| V2 stream returns | A/B/C | **PASS** — verified 2026-07-28 |
| V3 endpoint refused | A/B/C | not yet executed |
| V4 fails before first frame | A/B/C | not yet executed |
| V5 remote-only refuge | A/B/C | not yet executed |
| V6 flapping ×10 | A/B/C | not yet executed |
| V7 decoder error | arm64/device | **BLOCKED** on rig A |
