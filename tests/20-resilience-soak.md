# 20 — Resilience soak and resource health

**Tag:** any for leak and stability behaviour; **arm64/device** before believing any *timing*
result, and **device** for the multi-hour run that actually resembles a passage.
**Rig:** A for the scripted soak, B or C for a realistic one.

Files [16](16-resilience-faults.md)–[19](19-resilience-lifecycle.md) each prove a failure is handled
**once**. This file exists because the failure mode that reaches a boat is different: nothing
crashes on the first outage, and something runs out on the fortieth. Sockets, threads, codec
instances, `NsdManager` listeners, coroutines and wake locks are all things this app allocates on a
reconnect path that a flaky AP can drive dozens of times an hour.

A soak is also the only way to catch a retry loop that has quietly **doubled**: two loops racing
where one should be looks identical to one loop in every single-shot test, and shows up here as
twice the reconnection rate in the emulator log.

---

## SETUP

```bash
cd emulator
python -m mfd_emulator --no-discovery --no-console --log-file emu.log
```

App connected and streaming. Take a baseline **before** starting — every assertion below is a
comparison, so a run without a baseline proves nothing:

```bash
"$ADB" shell ps -A -o PID,ETIME,NAME | grep openhelm            # PID must not change all run
PID=$("$ADB" shell pidof dev.openhelm.app | tr -d '\r')
"$ADB" shell dumpsys meminfo $PID | head -20        > soak_mem_before.txt
"$ADB" shell ls /proc/$PID/fd 2>/dev/null | wc -l   > soak_fd_before.txt
"$ADB" shell cat /proc/$PID/status | grep Threads   > soak_threads_before.txt
```

> `/proc/<pid>/fd` may be unreadable without root on some images. If so, fall back to
> `dumpsys meminfo` plus the crash check — a socket leak large enough to matter also shows as
> native growth.

---

## K1 — Control flap ×30 (`close-rrc`)

The commonest real disruption: the display drops the socket, the listener stays up.

**STEPS**

```bash
for i in $(seq 1 30); do
  python scripts/fault.py close-rrc
  sleep 6                                   # reconnect happens on the first backoff (~1 s)
  echo "cycle $i $(date +%T)"
done
```

**EXPECTED** all 30 recover. The thirtieth reconnects as fast as the first (~1 s) — a *rising*
reconnect time across cycles means backoff state is not being reset on success.

**VERIFY**

```bash
python scripts/fault.py log 200 | grep -c "client connected"      # expect ~31 (initial + 30)
"$ADB" logcat -d AndroidRuntime:E *:F | tail -20                  # expect nothing
```

Count the connections: **materially more than 31 means a duplicated retry loop**, which is the
bug this count exists to catch. Fewer means some cycle never recovered.

**PASS** ~31 connections, no crash, same PID. **FAIL** on a missed cycle, a growing reconnect
latency, or a connection count well above the cycle count.

---

## K2 — Full outage cycling ×10 (`net-down` / `net-up`)

Each cycle drives the complete path: degrade both halves → exhaust the retry budget → give up to
the connect screen → recover on demand. This is the heaviest cycle the app has.

**STEPS**

```bash
for i in $(seq 1 10); do
  python scripts/fault.py net-down
  sleep 75                                   # past the ~30-60 s give-up
  python scripts/fault.py net-up
  sleep 5
  tap_text "Scan again"
  sleep 25
  "$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -o 'text="[^"]*"' \
    | grep -Ei "connected|connection lost" | head -2
done
```

**EXPECTED** every cycle ends `Connected` with video restored. No cycle leaves the app on the
connect screen after `Scan again`, and none leaves a dimmed panel on a live connection.

**PASS** 10/10 fully recovered. **FAIL** on any cycle that does not return to a working session.

---

## K3 — Video flap ×20 (`stream-drop` / `stream-resume`)

Video is the resource-heaviest path — a `MediaCodec` instance, a decode thread, a `Surface`, an
RTSP socket and an RTP socket pair per attempt.

**STEPS**

```bash
for i in $(seq 1 20); do
  python scripts/fault.py stream-drop;   sleep 13
  python scripts/fault.py stream-resume; sleep 16
done
```

**EXPECTED** all 20 recover; the overlay clears every time; `fps` returns to ~15 each cycle.

**VERIFY** the resource comparison below — this is the case most likely to move the numbers.

**PASS** 20/20 recovered with no leak trend. **FAIL** on a stuck overlay, a black pane that never
recovers, or clear monotonic growth.

---

## K4 — Resource comparison (run after K1–K3)

```bash
"$ADB" shell dumpsys meminfo $PID | head -20        > soak_mem_after.txt
"$ADB" shell ls /proc/$PID/fd 2>/dev/null | wc -l   > soak_fd_after.txt
"$ADB" shell cat /proc/$PID/status | grep Threads   > soak_threads_after.txt
diff soak_mem_before.txt soak_mem_after.txt
diff soak_fd_before.txt  soak_fd_after.txt
diff soak_threads_before.txt soak_threads_after.txt
```

**EXPECTED / thresholds**

| Metric | Acceptable | Investigate |
|---|---|---|
| PID | unchanged for the whole run | any change = a crash-restart that the crash check missed |
| Java heap | ± a few MB, non-monotonic | steady climb across all three soaks |
| Native heap / Graphics | small rise, plateaus | tens of MB monotonic → a `MediaCodec`/`Surface` not released |
| Open fds | back near baseline (± ~10) | one or two extra **per cycle** → a socket leak |
| Threads | back near baseline (± ~5) | one extra per cycle → a decode `HandlerThread` not quit, or a coroutine leak |

The per-cycle shape is what matters, not the absolute number: a leak is a *rate*. If a metric grew,
re-run the relevant single soak with half the cycles — a leak halves with it, noise does not.

**PASS** all metrics inside the acceptable column. **FAIL** on any per-cycle growth trend.

---

## K5 — Long unattended soak — **device**

The scripted cycles above are deliberately harsh and fast. A boat is the opposite: hours of nothing,
punctuated by real outages. Some failures only appear on that timescale — a wake lock never
released, a `NsdManager` registration that ages out, a codec that degrades after hours of
continuous decode, Doze interacting with a foreground session.

**STEPS** on a real phone (rig B or C), connected and streaming, screen on, for **≥ 4 hours**, with
a fault every ~20 minutes chosen at random from `close-rrc`, `stream-drop`+`stream-resume`, and
`net-down`+`net-up`.

**EXPECTED** at the end: same PID, still able to connect, memory not materially above the one-hour
mark, video still at full frame rate, no ANR in the log.

**VERIFY**

```bash
"$ADB" logcat -d | grep -iE "ANR in|FATAL|lowmemory|Slow operation" | tail -40
"$ADB" shell dumpsys meminfo $PID | head -20
"$ADB" shell dumpsys power | grep -iA3 "Wake Locks"     # nothing held by openhelm after disconnect
```

**PASS/BLOCKED** — **BLOCKED** on rig A: an AVD's software decoder and absent power management make
a multi-hour result unrepresentative of the thing being tested.

---

## K6 — Known limitation: a hung display is not detected quickly

Recorded here so a soak run does not "discover" it as a new defect.

`stall-on` leaves the socket ESTABLISHED while the display stops reading. The app correctly reports
`Connected` — it is, by every signal available to it — and frames queue in the kernel and arrive
**late, in a burst**, when the stall is released. The write watchdog (`WRITE_STALL_MS`, 5 s) only
fires once a write actually *blocks*, which requires the socket send buffer to fill: thousands of
11-byte control frames. This is inherent to a write-only protocol with no heartbeat; the MFD never
speaks on the control socket, so there is nothing to time out on.

**Verified 2026-07-28**: 8 frames sent during a stall all arrived with a single timestamp on
release. Two mitigations are recorded in [16](16-resilience-faults.md); neither is implemented, and
neither should be attempted without device evidence — the obvious one (drop stale frames) risks
dropping a key UP, which on real hardware means a runaway cursor.

**Expected result of any soak that includes `stall-on`:** the app stays `Connected` and delivers
late. That is the documented behaviour, not a failure.

---

## PASS/FAIL — file summary

| ID | Requires | Status |
|---|---|---|
| K1 control flap ×30 | A/B/C | not yet executed |
| K2 full outage ×10 | A/B/C | not yet executed |
| K3 video flap ×20 | A/B/C | not yet executed |
| K4 resource comparison | A/B/C | not yet executed |
| K5 long soak ≥4 h | device | **BLOCKED** on rig A |
| K6 hung-display limitation | A/B/C | **documented** — verified 2026-07-28 |
