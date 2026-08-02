# 16 — Resilience under injected faults (the outage matrix)

**Tag:** any (AVD is sufficient — every fault here is control-plane or RTSP-session behaviour, not
transport timing)
**Rig:** A (AVD + emulator). Video must run in a mode the AVD can render — this run used the
simulator's TCP-interleaved transport, chosen in the debug-only Manual-connect dropdown.

> **Resilience suite, 1 of 5.** This file is the core outage matrix and the one with recorded
> results. The others go deeper on one axis each:
> [17](17-video-resilience.md) video pipeline · [18](18-discovery-resilience.md) discovery ·
> [19](19-resilience-lifecycle.md) failures crossed with lifecycle and user action ·
> [20](20-resilience-soak.md) soak and resource health. All five share the fault harness described
> in the [README](README.md#fault-injection--the-harness-the-resilience-tests-1620-run-on).

Verifies the resilience contract added on 2026-07-28: **no connection or video failure may crash
or freeze the app**; disruptions retry a bounded number of times; a permanently broken connection
returns the user to the scanning screen; during a disruption the command controls grey out and go
inert while the last video frame stays visible under a "delayed, not realtime" warning.

---

## SETUP

Emulator with the fault-control server (default port 8571):

```bash
python -m mfd_emulator --no-discovery --no-console --log-file .run/events.log
```

`--no-discovery` is deliberate: these scenarios drive the app from a **remembered** endpoint, which
is the path that runs unattended at launch. Point the app at `10.0.2.2:8555:50000:RAYMARINEMFD:10`
via Manual connect once; every later scenario reconnects from the recents button.

Faults are fired with `python scripts/fault.py <command>` (see `--help`). Each scenario's evidence
is the emulator event log (`fault.py log 20`), a screenshot, and — for the negative assertions —
the **absence of RRC frames while a positive control exists** (tapping the same key while connected
must log `button MENU down/up`; the repo has burned itself on absence-of-evidence before).

---

## STEPS / EXPECTED

### S1 — Transient control drop (`close-rrc`)

The display closes the control socket; its listener stays up.

| | |
|---|---|
| **EXPECT** status line | `Reconnecting · the display closed the connection` (red) |
| **EXPECT** side panel | visibly dimmed |
| **EXPECT** status bar | palette / Mirror-Remote / Disconnect stay at full brightness and stay usable |
| **EXPECT** video | unaffected, still streaming |
| **EXPECT** recovery | reconnects on the first backoff (~1 s) with no user action |

### S2 — Control refused (`rrc-down`) → give up → scanning screen

The display leaves the network; every reconnect is refused.

| | |
|---|---|
| **EXPECT** during | `Reconnecting · the display refused the connection`, panel dimmed |
| **EXPECT** key taps | produce **no** RRC frames (positive control: the same tap logs frames while connected) |
| **EXPECT** give-up | after ~5 attempts / ~30 s of backoff, the session ends by itself |
| **EXPECT** after | connect screen: ring reads `Scan again`, `Connection lost`, and the reason underneath; the remembered display is offered as a one-tap button |

### S3 — Recovery (`rrc-up` + Scan again)

| **EXPECT** | probe connects, session restored within a few seconds; `Connection lost` clears |

### S4 — Video only (`stream-drop`)

FFmpeg killed; the RTSP endpoint still answers, so the session starves.

| | |
|---|---|
| **EXPECT** overlay | `VIDEO DELAYED — NOT LIVE` over the **still-visible** last frame, with the reason and `Reconnecting…` |
| **EXPECT** status line | stays `Connected` — video failure must not end the session |
| **EXPECT** panel | **not** dimmed; keys still send frames |
| **EXPECT** `stream-resume` | video returns on its own; overlay clears |

### S5 — Everything (`net-down`) → give up → `net-up`

| | |
|---|---|
| **EXPECT** during | control *and* video degrade together: `Reconnecting` + the stale-video overlay |
| **EXPECT** give-up | connect screen with `Connection lost` |
| **EXPECT** after `net-up` | Scan again reconnects control **and** video |

### S6 — Hung display (`stall-on`) — documents a limitation, not a pass/fail

The socket stays ESTABLISHED; the display stops reading.

| **EXPECT** | app continues to show `Connected` (it genuinely is, at the TCP level), and frames sent during the stall are **delivered late, in a burst**, when `stall-off` releases it |

---

## VERIFY (results — 2026-07-28, OpenHelmTab35 API 35, debug build)

| # | Result | Evidence |
|---|---|---|
| S1 | **PASS** | `13:41:04 FALT closed 1 RRC control socket(s)` → `13:41:05 RRC client connected`. Screenshot: red `Reconnecting · the display closed the connection`, panel dimmed, status-bar controls bright, video 20 fps. |
| S2 | **PASS** | `13:41:47 RRC listener DOWN`. Three Menu taps during the outage produced **no** frames (positive control at 13:37:10 logged `button MENU down/up` from the same coordinate). Gave up between 13:42:17 and 13:42:55 (T0+~35 s) → connect screen, `Connection lost` / `The display refused the connection`, `Scan again`, `10.0.2.2` recents button. |
| S3 | **PASS** | `rrc-up` 13:44:20 → probe 13:44:22.805 → session 13:44:23.533 → `Connected · 10.0.2.2`. |
| S4 | **PASS** | `13:44:59 RTSP stream dropped`. At +16 s: `VIDEO DELAYED — NOT LIVE` / "This is the last picture received, not realtime — DESCRIBE rejected: RTSP 404. Reconnecting…", chart still legible beneath. Status line still `Connected`; panel bright; `13:45:16 button MENU down/up` proves keys still work. `stream-resume` restored video with no user action. |
| S5 | **PASS** | `net-down` 13:47:11: both `Reconnecting · the display refused the connection` and the stale-video overlay simultaneously. Gave up at ~13:48:13 (T0+62 s) → `Connection lost`. `net-up` + Scan again → Connected with video at 15 fps. |
| S6 | **As documented** | During the stall the app kept showing `Connected`; on `stall-off` all 8 queued frames arrived with one timestamp (`13:46:31.213`). See the limitation below. |
| — | **No crash, no freeze** | One process (pid 3968) survived every scenario — 9 min uptime across the whole run. `logcat` has zero `FATAL EXCEPTION` / `ANR in` / process-death lines for `dev.openhelm`. The only app-tagged output is the retry loop's own `W OpenHelm: Connection attempt failed … ECONNREFUSED`, i.e. failures logged loudly and survived. |

### Known limitation surfaced by S6

A display that keeps the TCP connection open but stops reading is **not** detected quickly. The
app's write watchdog fires only once a write actually *blocks*, which needs the socket send buffer
to fill — thousands of 11-byte control frames. Until then the app correctly reports `Connected`
(the connection is up by every signal available to it), and frames queue in the kernel and arrive
late in a burst rather than being dropped as stale.

This is inherent to a write-only protocol with no heartbeat: the MFD never speaks on the control
socket, so there is nothing to time out on. The watchdog still bounds the *worst* case. Two options
if this matters in the field, neither taken here for want of device evidence:

1. Drop frames older than N ms at the head of the send queue — **must never drop a key UP**, or the
   display auto-repeats forever; would need `send()` to know which frames are droppable.
2. Treat a long run of `trySend` drops while `Connected` as a reconnect trigger.

### Notes on the run

- **Give-up latency varied**: ~35 s (S2) vs ~62 s (S5). Both are within the designed band
  (5 attempts × backoff 1/2/4/8/15 s plus connect overhead); the longer case had the video
  pipeline retrying concurrently, whose `wifi.bind()` shares a mutex with the control loop's.
- The emulator's own `stop_listening` had to be fixed during this run: from Python 3.12
  `Server.wait_closed()` waits for every *connection handler*, and the app's control handler is
  parked in `read()` by design, so the fault hung. Clients are now dropped first and the wait is
  bounded. Worth knowing before adding faults that close servers.

## PASS/FAIL

**PASS** — S1–S5 met every expectation; S6 behaved as analysed and is recorded as a limitation with
options rather than a defect. Nothing crashed, nothing froze, and every disruption either recovered
on its own or ended by handing the user back to the scanning screen.
