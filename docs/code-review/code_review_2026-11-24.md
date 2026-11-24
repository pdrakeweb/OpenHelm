# OpenHelm code review — 2026-11-24

Scope: the Android client (`app/`) and the wire-format module (`protocol/`), with the weight on
disconnect, reconnect and communication-failure paths. The Python emulator and the Compose layout
code were skimmed, not reviewed.

Every item marked **Fixed** landed in the same change as this document.

## Verification, and its limits

No Android SDK was available where this review was done, so **nothing here has run on a device or
against a real display.** What was done instead:

- The control, video, discovery and network code was type-checked with a standalone `kotlinc`
  against framework stubs. `MainViewModel` (Compose/Lifecycle dependencies) was **not** compiled.
- The JVM suites pass: 56 tests across `:protocol`, `RtspSessionTest` and the new `SocketOwnerTest`.
- `RrcClient` and `VideoPlayer` were run unmodified against local sockets with the Android classes
  faked (a scripted RTSP/RTP server for video). The same harness run against the previous revision
  reproduces F1 and F2 on the first or second iteration.
- `TcpLiveness` and the `H264Decoder` changes are exercised by none of that. They need a device.

Manual cases for the new behaviour are in `tests/19` (L7, L8) and the constants table in
`tests/README.md`.

## Findings

### F1 — A stopped loop closes its successor's socket (High) — Fixed

`RrcClient` and `VideoPlayer` kept "the current socket" in fields shared by every loop they ever
ran. `disconnect()`/`stop()` clear the job reference, so the next `connect()`/`start()` has nothing
to join and launches while the old loop is still unwinding; the old loop's cleanup then closes
whatever is in the shared field — the new loop's socket.

- Control: Disconnect followed by reconnect produced a spurious `Reconnecting: Socket closed`.
- Video: `stop()` deliberately leaves the pipeline up to 2 s to send TEARDOWN. Any `start()` inside
  that window — screen off and straight on, Mirror off/on — failed with `Socket closed` and paid a
  3 s backoff, or lost the TEARDOWN. It could also configure a second `MediaCodec` on a Surface the
  first had not yet released.

Fix: each loop owns a `SocketOwner`; only the current loop may publish state (checked under the same
lock as start/stop, replacing the "re-assert Idle after join" workaround); a new loop always waits
for its predecessor, including one already stopped; `H264Decoder.stop()` waits for the release.

### F2 — A socket created after the close blocks with nobody to close it (Medium) — Fixed

The stopper closed the current socket, but a loop that was between sockets (waiting in
`wifi.bind()`) then created a fresh one and blocked in `connect` for up to 45 s, holding up the
next loop behind `join()`. `SocketOwner.shutdown()` latches: a socket adopted afterwards is closed
immediately.

### F3 — A vanished display shows "Connected" for minutes to hours (High) — Fixed, untested on hardware

The write watchdog added for the previous review's H4 only fires when `write()` *blocks*, which
needs a full send buffer — thousands of 11-byte frames. In practice a key press into a dead link
returns instantly, and the socket errors only when TCP gives up retransmitting (~15 min); an idle
link waits for the default keepalive (2 h). This applies whenever the display disappears without
the phone losing Wi-Fi: display powered off behind a separate access point, cable pulled, router
rebooted.

Fix: `TcpLiveness` sets `TCP_KEEPIDLE/INTVL/CNT` (5 s / 2 s / 3) and `TCP_USER_TIMEOUT` (10 s) on
the control socket. Best-effort; a refusal leaves the old behaviour.

Not covered: a display whose application hangs while its kernel keeps acknowledging
(`tests/20` K6). Nothing short of a protocol-level reply can detect that.

Behaviour change to expect: an outage longer than ~10 s now shows `Reconnecting` where it used to
sit silently on a zombie socket.

### F4 — Every retry of a dead video link waits 15 s for a TEARDOWN answer (Medium) — Fixed

`runPipeline`'s `finally` sent TEARDOWN and read the response on the 15 s negotiation timeout. After
a stall — where the display is usually the thing that went away — each cycle was 10 s detection +
15 s TEARDOWN + backoff. The wait is now 1.5 s, and TEARDOWN is skipped when no session was ever
established.

### F5 — Video backoff never resets (Medium) — Fixed

`attempt` only grew for the life of a `start()`. After five blips over a long session, every
further one-second dropout cost the 15 s maximum backoff. It now restarts after any attempt that
delivered media.

### F6 — Media arriving but nothing rendering reads as "Streaming" (Medium) — Fixed

Stall detection watched for silence on the socket. A decoder waiting on a keyframe after one lost
packet, or one that has stopped producing output without raising an error, leaves the last frame
on screen under a state of `Streaming`. A watchdog now fails the attempt after 15 s of media with
no rendered frame.

Assumption worth checking on hardware: that the display's encoder sends keyframes more often than
every 15 s, or sends one at the start of a new session. If neither holds, loss recovery will loop.

### F7 — Exceptions on the decoder thread kill the process (High) — Fixed, untested on hardware

`MediaCodec` callbacks run on a `HandlerThread`, outside every coroutine `try`. `getInputBuffer`,
`queueInputBuffer` and `releaseOutputBuffer` throw `IllegalStateException` once the codec is in its
error state or the Surface is gone, and `ByteBuffer.put` throws on an access unit larger than the
input buffer. All are now caught and routed to `onError`; oversized units are dropped.

Related: callbacks already queued when `stop()` ran could still report — a first frame setting
`Streaming` after the session was stopped, or an error failing the next pipeline. They are now
ignored after `stop()`.

### F8 — A display that accepts and immediately drops flaps forever (Medium) — Fixed

`attempt` reset on every successful connect, so accept-then-close never reached the retry limit:
`Connected`/`Reconnecting` once a second, indefinitely. The budget now refills only after a
connection has held 2 s.

### F9 — The connect screen is a dead end after an outage (Medium) — Fixed, not compiled

After the control loop gives up, the connect screen probes the remembered displays once, scans for
12 s, and stops. A display that takes longer than that to boot is never found until someone taps
"Scan again". The screen now re-probes remembered local displays every 10 s while visible.

### F10 — Simulation mode can end up driving a real display (Medium) — Fixed, not compiled

The launch probe and an in-flight mDNS resolve both outlive the connect screen. Entering simulation
within that window let them connect underneath it, after which simulated key presses went to the
display. Entering simulation now cancels both, the auto-connect paths check for it, and no command
is sent while it is on.

### F11 — `firstReachable` can crash the app (Medium) — Fixed

It caught `IOException` and `IllegalArgumentException` only, and runs in `viewModelScope`, which has
no exception handler. `registerNetworkCallback` throws unchecked exceptions (too many requests,
missing permission), as can address resolution. It now catches everything but cancellation.

### F12 — A loss reason can outlive the session it belonged to (Low) — Fixed

A loop cancelled at the moment it exhausted its retries still recorded `lastLoss`, which then
surfaced as "connection lost" on the next unrelated return to Idle.

### F13 — RTP from other hosts keeps a dead stream alive (Low) — Fixed

The stall timer was the socket timeout, which any datagram resets — including the ones rejected
for coming from the wrong address. The timer now runs from the last packet from the display.

### F14 — Discovery: start/stop race, and one failed resolve hides the display (Low) — Fixed

`onStartDiscoveryFailed` calls `stop()` from NsdManager's thread while `start()` may still be
assigning the listener list, leaking the second listener. Both are now synchronized. A resolve that
reports failure (commonly `FAILURE_ALREADY_ACTIVE` right after a rescan) dropped the service for
the whole search; it is now retried twice.

### F15 — RTSP `Content-Length` is trusted (Low) — Fixed

A response could ask for a 2 GB allocation. Capped at 64 KiB.

## Open items, not changed

| # | Item | Why it was left |
|---|---|---|
| O1 | Transport fallback is symmetric: UDP with no media falls back to TCP-interleaved against a real display, which `MainViewModel.transportFor` otherwise goes out of its way to prevent. | Costs one wasted 10 s cycle per alternation; changing it alters documented simulator behaviour. Worth a decision. |
| O2 | After a sequence gap the depacketizer resumes mid-frame. A multi-slice keyframe that lost only its first slice would be emitted and decoded torn. | Depends on whether the display's encoder uses multiple slices; unknown. |
| O3 | Reconnect backoff is not interrupted when the app returns to the foreground or Wi-Fi comes back; the user can wait out up to 15 s. | Small, and wants a design for "network available" signalling rather than a patch. |
| O4 | `gradlew` is committed without the executable bit (the release workflow `chmod`s it; the README's `./gradlew` commands fail on a fresh clone). | One-line fix, unrelated to this change. |
| O5 | `RrcClient`, `VideoPlayer` and `MainViewModel` have no automated tests because they construct Android types directly. The throwaway harness used for this review shows what an interface over `WifiNetworkBinder` and the decoder would buy. | Refactor, not a fix. |
