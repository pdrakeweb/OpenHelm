# OpenHelm code review — 2026-07-28

**Scope:** the full `openhelm/` codebase — `protocol/` (5 source files, 4 test files) and `app/`
(30 source files, 6 test files), ~8,400 lines of Kotlin, plus the Gradle build, manifest, and
docs. Every source file was read in full. This is a code review; the UI/UX ground was covered by
the [2026-07-26 design review](../design-review/design_review_2026-07-26.md) and is not re-litigated
here except where the code contradicts its own design intent.

Line numbers refer to the working tree as of this date (branch `main`, after commit `a3ff716`).

---

## 1. Executive summary

OpenHelm is an unusually well-built codebase for its size. The module split is clean and the
dependency direction is strictly one-way (`protocol` is pure JVM with `explicitApi()`, `app`
consumes it; UI → ViewModel → singleton services; nothing points backwards). The protocol layer is
verified against golden byte vectors from an independent reference implementation, the
security-sensitive surfaces (mDNS-sourced strings, persisted records, user-facing error text) are
treated as hostile input and tested as such, and the comments are genuinely exceptional — they
record *why*, cite the defect that motivated each decision, and are honest about trade-offs (see
`Dial.kt`'s touch-target table, which documents its own non-compliance rather than hiding it).

The defects cluster in one place: **the connection/video lifecycle plumbing** — `RrcClient`,
`VideoPlayer`, `WifiNetworkBinder`, `MfdDiscovery`. These singletons run concurrent retry loops
over blocking sockets, and the seams between coroutine cancellation, blocking I/O, and shared
mutable state have several real holes. That is also exactly the code that runs unattended on flaky
boat Wi-Fi, where the failure paths are the product.

**Top 3 concerns:**

1. **A decoder failure silently freezes the video while the app continues to claim it is live**
   ([C1](#c1)). The app's own central safety invariant — a stale chart must never be mistakable
   for a live one, enforced elsewhere by the `StaleVideoOverlay` scrim — has a hole: `MediaCodec`
   errors are swallowed in an empty callback, the RTP loop keeps running, and `VideoState` stays
   `Streaming` over a frozen frame with no scrim.
2. **Only `IOException` is treated as an expected failure; anything else permanently kills a
   reconnect loop** ([C2](#c2)). Several non-IO exceptions are reachable from *network-controlled
   input* (an mDNS-advertised `rtspPath` that is not a valid URI, an out-of-range port from the
   manual-connect parser), and one from routine platform behaviour (codec reclaim). The
   `AppScope` exception handler keeps the process alive but the retry loop it was protecting is
   the thing that dies — the comment in `AppModule.kt` claims otherwise.
3. **Concurrency races in the shared singletons** ([H1](#h1)–[H4](#h4)): two independent retry
   loops re-entering an unsynchronized `WifiNetworkBinder` and unregistering each other's
   callbacks; `disconnect()`/`stop()` racing the loop they cancel so the UI can stick in
   `Reconnecting`/`Failed` forever; and endpoint-switch restarts that `cancelAndJoin()` behind a
   non-interruptible blocking socket call.

None of this diminishes the overall standard — most Android codebases several times this age have
worse plumbing. But this app aspires to be trusted at a helm, near an autopilot, and the items in
§2 are exactly the class of bug that erodes that trust in the field.

---

## 2. Critical issues (must fix before any release)

<a name="c1"></a>
### C1 — Decoder errors are swallowed; frozen video keeps presenting as live

- **Severity:** Critical (violates the app's own stated safety invariant)
- **Where:** [VideoPlayer.kt:135](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt)
  (`onError = { /* surfaced through the stats/state below when the loop breaks */ }`),
  [H264Decoder.kt:105-107](../../app/src/main/kotlin/dev/openhelm/app/video/H264Decoder.kt)

The comment on the empty `onError` lambda is wrong: a `MediaCodec.CodecException` does **not**
break the receive loop. `receiveUdp` keeps pulling RTP packets and `submit()` keeps posting them
to a dead codec; nothing throws on the pipeline coroutine, so `VideoState` remains `Streaming`,
`VideoPane` renders no overlay, and the last decoded frame sits on the `TextureView` at full
brightness — precisely the "chart that looks live but is minutes stale" scenario the
`StaleVideoOverlay` documentation calls the exact mistake the scrim exists to prevent.

This is not a theoretical path. Codec reclaim (`ERROR_RECLAIMED`) is routine on Android when the
app loses foreground priority; transient decoder faults on malformed input happen on real
hardware. Whenever it happens, the failure is *invisible by construction*.

**Fix:** make the decoder error a pipeline failure. Simplest shape: have `VideoPlayer` pass an
`onError` that records the reason and closes `rtspSocket`/`rtpSocket` (both are `@Volatile` and
closing them is thread-safe); the receive loop then fails with an `IOException`, the existing
retry machinery takes over, and `VideoState.Failed` puts the scrim up. Add the decoder-error
reason into the `Failed` state rather than discarding `e.diagnosticInfo`.

<a name="c2"></a>
### C2 — Any non-`IOException` permanently kills a retry loop; several are reachable from network-controlled input

- **Severity:** Critical
- **Where:**
  - [VideoPlayer.kt:95-101](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt) — the
    retry loop catches only `WifiUnavailableException` and `IOException`
  - [VideoPlayer.kt:122](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt) —
    `URI(rtspUrl)` throws unchecked `URISyntaxException`; `rtspPath` comes verbatim from an mDNS
    TXT record ([MfdDiscovery.kt:158](../../app/src/main/kotlin/dev/openhelm/app/discovery/MfdDiscovery.kt))
  - [RrcClient.kt:137-164](../../app/src/main/kotlin/dev/openhelm/app/rrc/RrcClient.kt) — same
    catch-only-IO pattern; `InetSocketAddress(host, port)` throws unchecked
    `IllegalArgumentException` for a port outside 0..65535
  - [Discovery.kt:79-87](../../protocol/src/main/kotlin/dev/openhelm/protocol/Discovery.kt) —
    `MfdEndpoint.parse` accepts any `Int` as a port (`"192.168.1.7:8554:99999999:path"` parses)
  - [H264Decoder.kt:60-115](../../app/src/main/kotlin/dev/openhelm/app/video/H264Decoder.kt) —
    `configure`/`start` can throw `IllegalStateException`/`IllegalArgumentException`
  - [AppModule.kt:27-39](../../app/src/main/kotlin/dev/openhelm/app/di/AppModule.kt) — the
    comment claims "the affected coroutine still dies and its own reconnect logic still applies"

The reconnect logic lives *inside* the coroutine that dies. When anything but an `IOException`
escapes `runPipeline`/`runConnection`, the `while (isActive)` loop is gone: the `AppScope`
`CoroutineExceptionHandler` logs it and the app survives, but `ConnectionState` freezes at
`Connecting`/`Reconnecting` (or `VideoState` at `Connecting`) with no further attempts, no
user-facing error, and no recovery short of killing the app. Two of the triggers are inputs an
arbitrary device on the Wi-Fi can choose (a TXT `rtsp-path` containing a space or `%` makes
`URI()` throw on every connection attempt to that remembered display — including the automatic
launch probe), and one is a value the user can type.

**Fix (three parts, all cheap):**
1. Validate ports (0–65535) in `MfdEndpoint.parse` and reject non-URI-safe `rtspPath` at the
   discovery merge (or build the URL with an encoder rather than string concatenation).
2. In both retry loops, catch `Exception` (not just `IOException`) as the per-attempt failure,
   preserving `CancellationException` propagation — the current catch-list makes "expected" a
   closed set in code that talks to a network and a media stack.
3. Reword the `AppModule` comment to match reality once the loops are self-healing.

---

## 3. High issues (fix in next sprint)

<a name="h1"></a>
### H1 — `WifiNetworkBinder` is shared mutable state with no synchronization; concurrent binds clobber each other

- **Severity:** High
- **Where:** [WifiNetworkBinder.kt:34, 46-78](../../app/src/main/kotlin/dev/openhelm/app/net/WifiNetworkBinder.kt)

`RrcClient.runConnection` calls `wifi.bind()` on every reconnect attempt
([RrcClient.kt:141](../../app/src/main/kotlin/dev/openhelm/app/rrc/RrcClient.kt)), and
`VideoPlayer.runPipeline` does the same on its own cadence
([VideoPlayer.kt:120](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt)). Both run
concurrently on `Dispatchers.IO`. `bind()` starts with `unbind()`, which unregisters whatever
callback is stored in the unsynchronized `callback` field — including the *other* caller's
callback while that caller is still suspended in `first.await()`. The victim's deferred then never
completes, it times out after 15 s, and reports `WifiUnavailableException` even though Wi-Fi is
fine. The window is widest exactly when it matters: while the phone is still associating with the
boat AP, both loops are inside `bind()` simultaneously. There is also a nastier interleaving:
`RrcClient.disconnect()` calls `wifi.unbind()` → `bindProcessToNetwork(null)` while the video
pipeline is mid-session (ordering in `MainViewModel.disconnect()` currently protects this, but
nothing in `WifiNetworkBinder` does).

**Fix:** make `bind()` a `Mutex`-guarded suspend (or make the class own a single cached
`Network` with reference counting). Unregister the callback as soon as `onAvailable` fires — it is
never used afterwards. Consider `unbind()` only dropping the process binding when no client holds
a session.

<a name="h2"></a>
### H2 — `disconnect()` / `stop()` race the loop they cancel; the UI can stick in `Reconnecting`/`Failed` forever

- **Severity:** High
- **Where:** [RrcClient.kt:119-125 vs 161-163](../../app/src/main/kotlin/dev/openhelm/app/rrc/RrcClient.kt),
  [VideoPlayer.kt:110-116 vs 104](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt)

`disconnect()` runs on the caller's thread: `cancel()` → `closeQuietly()` → `_state.value = Idle`.
Meanwhile the connection coroutine, unblocked by the socket close, is unwinding on an IO thread:
`pumpUntilFailure` catches the `IOException`, returns the reason, and the loop body executes
`attempt++; _state.value = Reconnecting(endpoint, reason)` **before** reaching the suspension
point (`delay`) where cancellation actually stops it. If that assignment lands after
`disconnect()`'s `Idle`, the state machine is dead — `AppRoot` keeps the user on `RemoteScreen`
showing "Reconnecting" with no loop behind it. `VideoPlayer.stop()` has the identical race with
`VideoState.Failed`.

**Fix:** guard the state writes with the loop's own liveness — e.g.
`if (currentCoroutineContext().isActive) _state.value = Reconnecting(...)` (checked after
`closeQuietly`, before the write), or route all state writes through the connection coroutine and
make `disconnect()` a message to it. A CAS-style `_state.compareAndSet` keyed on a generation
counter also works and removes the race entirely.

<a name="h3"></a>
### H3 — Endpoint-switch restarts can hang behind a blocked socket: `cancelAndJoin()` on non-interruptible I/O

- **Severity:** High
- **Where:** [RrcClient.kt:74-81](../../app/src/main/kotlin/dev/openhelm/app/rrc/RrcClient.kt),
  [VideoPlayer.kt:88-92](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt),
  [RtspSession.kt:146-179](../../app/src/main/kotlin/dev/openhelm/app/video/RtspSession.kt)

`connect(B)` launches a new job whose first act is `previous?.cancelAndJoin()`. Coroutine
cancellation does not interrupt blocking socket calls, and unlike `disconnect()`, the restart path
**never closes the previous socket**. If the previous loop is inside `socket.connect(...)` with its
45-second timeout ([RrcClient.kt:148](../../app/src/main/kotlin/dev/openhelm/app/rrc/RrcClient.kt)),
tapping a different display stalls the new connection for up to 45 s while the UI shows
"Connecting". Worse on the video side: the RTSP socket has **no `soTimeout`**, so a server that
accepts the TCP connection and then never answers `DESCRIBE` blocks `readLine()` indefinitely —
`start()`'s `cancelAndJoin()` then never returns and video never starts again until a full
`stop()`.

**Fix:** on restart, close the previous attempt's sockets before (or instead of) joining —
`closeQuietly()` is already idempotent. Set a generous `soTimeout` (e.g. 15 s) on the RTSP socket
for the negotiation phase; the interleaved read loop can re-set it to 0/stall-timeout afterwards.

<a name="h4"></a>
### H4 — No control-channel liveness: a silently dead link shows "Connected" while key presses vanish

- **Severity:** High
- **Where:** [RrcClient.kt:144-152, 171-193](../../app/src/main/kotlin/dev/openhelm/app/rrc/RrcClient.kt)

The reader detects an orderly FIN, but a half-open connection (AP power-cycled, phone walked out
of range and back — routine on boats) is invisible: `keepAlive = true` uses the kernel default
(~2 h idle), TCP retransmission takes ~15 min to error a blocked write, and until then the writer
sits blocked in `output.write` while the frame channel silently drops the oldest of 64 queued
frames. The user sees "Connected · host", presses keys, and nothing happens — with no signal that
anything is wrong. The RRC protocol has no application-level ping to borrow, but the failure is
still detectable.

**Fix:** add a write watchdog: timestamp before each `write/flush`, and a supervisor (or the
existing reader coroutine on a schedule) closes the socket if a write has been in flight longer
than a few seconds — the existing reconnect machinery then handles it honestly. Alternatively (or
additionally) surface send-queue saturation: if `trySend` has been dropping frames for > N
seconds while state is `Connected`, force a reconnect cycle.

---

## 4. Medium issues (technical debt to schedule)

### M1 — RTP receive path accepts datagrams from any source

- **Severity:** Medium (security)
- **Where:** [VideoPlayer.kt:194-214](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt)

`receiveUdp` renders any well-formed RTP/H.264 packet that arrives on the (randomly chosen but
observable) port — the source address is never checked. Anyone on the same Wi-Fi can therefore
inject video and replace the chart on screen. This app has clearly thought about hostile LAN peers
(`LocalAddresses`, the `EndpointStore` escaping, `sanitiseReason`), and spoofable *chart imagery*
is a strictly worse injection than any of those. **Fix:** after `setupUdp`, record the display's
address and drop any datagram whose `datagram.address` differs (the server may legitimately send
from a port other than the negotiated one, so filter on address, not port).

### M2 — `MfdDiscovery` leaks listeners on restart-after-failure; a stuck resolve stalls discovery forever

- **Severity:** Medium
- **Where:** [MfdDiscovery.kt:74-113](../../app/src/main/kotlin/dev/openhelm/app/discovery/MfdDiscovery.kt)
  (restart), [MfdDiscovery.kt:104-106](../../app/src/main/kotlin/dev/openhelm/app/discovery/MfdDiscovery.kt)
  (failure path), [MfdDiscovery.kt:131-143](../../app/src/main/kotlin/dev/openhelm/app/discovery/MfdDiscovery.kt)
  (resolve)

Three related problems. (a) `onStartDiscoveryFailed` sets `_searching = false` but leaves
`listeners` populated and the multicast lock held; a subsequent `start()` (the "Scan again"
button) passes the `_searching` guard and overwrites both `listeners` and `resolveWorker` without
stopping the old ones — the possibly-successful second listener of the pair stays registered with
`NsdManager` forever, and two workers then drain one queue concurrently, defeating the
serialization that exists because `NsdManager` rejects concurrent resolves. Repeated failures
accumulate leaked listeners until `NsdManager`'s per-app limit breaks discovery until process
death. (b) `resolve()` has no timeout: one `resolveService` call that never invokes its callback
(a known `NsdManager` failure mode) wedges the single worker and every queued resolution behind
it, silently. (c) The multicast lock stays held after a start failure — a battery cost with no
benefit. **Fix:** on start failure, run the full `stop()` teardown; wrap `resolve()` in
`withTimeoutOrNull(5_000)`; cancel the old worker before assigning a new one.

### M3 — RTSP keepalive assumes `GET_PARAMETER`; an unsupporting server tears the session down every timeout interval

- **Severity:** Medium
- **Where:** [RtspSession.kt:79-85](../../app/src/main/kotlin/dev/openhelm/app/video/RtspSession.kt),
  [VideoPlayer.kt:216-221](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt)

`keepalive()` sends `GET_PARAMETER` and `request()` throws on any non-200, so a server that
answers `501 Not Implemented` (legal; `OPTIONS` is the universal keepalive) kills the pipeline
every `timeout/2` seconds, producing rhythmic video drops that would be miserable to diagnose in
the field. The known units evidently accept it, but this codebase is otherwise careful to degrade
rather than assume. **Fix:** fall back to `OPTIONS` on 4xx/501, or treat a non-200 keepalive
answer as ignorable (the session either survives to the next PLAY-refresh or dies visibly).

### M4 — FU-A reassembly is quadratic and the hot path churns allocations

- **Severity:** Medium (performance)
- **Where:** [RtpH264.kt:117-134](../../protocol/src/main/kotlin/dev/openhelm/protocol/video/RtpH264.kt),
  [RtpH264.kt:148-157](../../protocol/src/main/kotlin/dev/openhelm/protocol/video/RtpH264.kt)

Each FU-A continuation executes `fuBuffer = buf + p.copyOfRange(...)` — a full copy of everything
accumulated so far, so a k-fragment NAL costs O(k²) bytes copied. An IDR at 800×480 split into
~35 MTU fragments copies ~1 MB per keyframe, plus a fresh `ByteArray` per packet (`START_CODE + p`
per NAL, `copyOfRange` per parse) at 25–30 packets/frame — steady GC pressure on the one path the
latency budget lives on. It works at this resolution; it is the first thing that will hurt if a
future unit streams 1080p. **Fix:** accumulate fragments in a reusable growable buffer (or a
`ByteArrayOutputStream` per AU) and build the access unit once at the marker.

### M5 — `openUdpPair` leaks sockets on its failure paths

- **Severity:** Medium (resource leak, low frequency)
- **Where:** [VideoPlayer.kt:249-281](../../app/src/main/kotlin/dev/openhelm/app/video/VideoPlayer.kt)

In the odd-port branch, `rtpEven` is bound and then `rtcp`'s bind can throw — the `catch` closes
only `rtp` (already closed in that branch), leaking `rtpEven`; up to 20 sockets per call in the
worst case. Both branches also leak the unbound `rtcp` `DatagramSocket` object on failure.
**Fix:** track both sockets in the `catch` (or restructure with a small `runCatching { ... }
.onFailure { closeAll() }` helper).

### M6 — `MainViewModel` is untested and untestable as written

- **Severity:** Medium (testability)
- **Where:** [MainViewModel.kt](../../app/src/main/kotlin/dev/openhelm/app/ui/MainViewModel.kt) (472 lines)

The auto-connect state machine — suppression after explicit disconnect, probe-then-scan ordering,
the `LocalAddresses` filter on the launch probe, `parsePalette`'s legacy mapping, route
transitions — is the most intricate *logic* in the app layer, and none of it is under test because
the ViewModel takes four concrete singletons (`RrcClient`, `MfdDiscovery`, `EndpointStore`,
`VideoPlayer`) whose constructors drag in Android networking. The project's stated test philosophy
(pure decision functions, JVM-only) is sound, but this class accumulated real decisions that are
no longer pure. **Fix:** extract interfaces for the four collaborators (they are already
API-shaped: `StateFlow` out, commands in), or extract the auto-connect decision logic into a pure
class the existing JVM suite can drive. `parsePalette` at minimum is trivially extractable and
carries upgrade-compatibility semantics that deserve a pinned test.

### M7 — `RtspSession`'s response parsing is untested because it is welded to `Socket`

- **Severity:** Medium (testability)
- **Where:** [RtspSession.kt:24-27, 146-212](../../app/src/main/kotlin/dev/openhelm/app/video/RtspSession.kt)

`readResponse`, `rememberSession` (the `timeout=` extraction), `setupUrl`'s three-way control
resolution, and the interleaved demux in `readInterleaved`/`skipTextResponse` are all
string/stream logic that would fit the project's JVM-test philosophy perfectly, but the class
takes a `Socket` rather than the `InputStream`/`OutputStream` pair it actually uses, so none of it
is covered — this in a module where the *other* wire parsers have golden-vector suites. **Fix:**
constructor-inject the two streams (the `Socket` overload can remain as a convenience); add tests
for status-line parsing, header folding, `Content-Length` bodies, `Session;timeout=`, and `$`-frame
demux with an embedded keepalive response.

---

## 5. Low / polish items

| # | Severity | Where | Issue → Fix |
|---|---|---|---|
| L1 | Low | [AndroidManifest.xml:14-20](../../app/src/main/AndroidManifest.xml) | `android:allowBackup` defaults to true: remembered displays (hosts, ports, serials, user-given boat names) go into device/cloud backups. Add `dataExtractionRules` (31+) / `fullBackupContent` and decide deliberately; the data is harmless enough to keep, but the decision should be explicit like everything else here. |
| L2 | Low | [app/build.gradle.kts:22-26](../../app/build.gradle.kts) | `isMinifyEnabled = false` for release: no R8 shrinking or obfuscation-resistant dead-code removal. For an open-source app obfuscation is moot, but shrinking Compose+Hilt typically halves the APK. Enable with default rules before first release. |
| L3 | Low | [build.gradle.kts](../../build.gradle.kts), [app/build.gradle.kts:40-66](../../app/build.gradle.kts) | Versions are string literals scattered across three build files (Hilt appears twice, plugin + two artifacts). Move to a `gradle/libs.versions.toml` catalog; wire Renovate/Dependabot when the repo goes public. Versions themselves are current as of this review. |
| L4 | Low | [VideoPane.kt:108-110](../../app/src/main/kotlin/dev/openhelm/app/ui/VideoPane.kt) | A new `Surface(st)` is created per `onSurfaceTextureAvailable` and never `release()`d — the native reference is dropped to the finalizer. Keep the reference and release it in `onSurfaceTextureDestroyed`. |
| L5 | Low | [VideoPane.kt:167](../../app/src/main/kotlin/dev/openhelm/app/ui/VideoPane.kt), [SimulatedRemoteScreen.kt:218](../../app/src/main/kotlin/dev/openhelm/app/ui/SimulatedRemoteScreen.kt) | `"%.1f".format(scale)` is default-locale — renders `×1,5` for comma-decimal locales (debug overlay and sim action field). Use `Locale.ROOT` or string templates. |
| L6 | Low | [VideoGestures.kt:68, 116](../../app/src/main/kotlin/dev/openhelm/app/ui/VideoGestures.kt) | `System.currentTimeMillis()` for the pinch-grace and move-throttle timing — wall clock, jumps with NTP/user changes. Use `SystemClock.uptimeMillis()` (or the `PointerEvent`'s own `uptimeMillis`). |
| L7 | Low | [EndpointStore.kt:59-66](../../app/src/main/kotlin/dev/openhelm/app/config/EndpointStore.kt) | Stale doc: "…or null to follow the system's light/dark setting." Since the `DefaultPalette` decision ([Theme.kt:27-35](../../app/src/main/kotlin/dev/openhelm/app/ui/Theme.kt)) null means DARK regardless of system setting. In a codebase whose comments are this load-bearing, drift is worth fixing. |
| L8 | Low | [MainViewModel.kt:58-65](../../app/src/main/kotlin/dev/openhelm/app/ui/MainViewModel.kt) | `remembered` and `recentShortlist` are two eager `stateIn`s over the same DataStore flow — a second collector for a `.take(4)`. Derive the shortlist from `remembered` (`map` on the StateFlow) instead. |
| L9 | Low | [RrcDecoder.kt:34-56](../../protocol/src/main/kotlin/dev/openhelm/protocol/RrcDecoder.kt) | `ArrayDeque<Byte>` boxes every byte and rebuilds frames element-by-element. Harmless at control-channel rates; a `ByteArray` ring or `okio.Buffer`-style windowing would be the idiomatic fix if this is ever fed video-rate data. Also note `RrcDecoder`/`RrcFrame` have no callers inside this repo besides tests (the MFD never speaks on the socket) — presumably the simulator's half lives in the parent repo; worth a KDoc line saying the decoder exists for tooling/simulators so the subtree-split repo doesn't read as shipping dead code. |
| L10 | Low | [Rrc.kt:22](../../protocol/src/main/kotlin/dev/openhelm/protocol/Rrc.kt) | `Rrc.MAGIC` is a `public val ByteArray` — mutable contents on an `explicitApi()` library surface. Expose a copy, or keep the array `private` and expose the four bytes only where needed (`RrcDecoder` is in-module). |

---

## 6. Positive observations

These are specific, not courtesy:

1. **Golden-vector protocol testing** ([RrcTest.kt:10-17](../../protocol/src/test/kotlin/dev/openhelm/protocol/RrcTest.kt)):
   conformance is asserted against byte vectors produced by an independent implementation
   validated against hardware — including the deliberately preserved wart (`"1.10"` parsed as hex
   `0x10`, [Discovery.kt:26-43](../../protocol/src/main/kotlin/dev/openhelm/protocol/Discovery.kt))
   and the rounding-vs-truncation midpoint case pinned at exactly 32768.
2. **Hostile-input discipline in unglamorous places.** `LocalAddresses` rejects octal-look
   octets, `.local` names, CGN space, and IPv4-mapped IPv6 — with tests that explain which half of
   the policy "has teeth" ([LocalAddressesTest.kt:8-14](../../app/src/test/kotlin/dev/openhelm/app/net/LocalAddressesTest.kt)).
   The `EndpointStore` record format escapes separators specifically because TXT-record bytes are
   attacker-chosen, and the test suite proves a forged newline cannot mint a second record. The
   user-facing failure string is sanitised so a hostile RTSP reason-phrase cannot pose as
   app-authored text in the safety banner ([RemoteScreen.kt:260-298](../../app/src/main/kotlin/dev/openhelm/app/ui/RemoteScreen.kt)).
   This is threat modelling most phone apps never do.
3. **The latency-first decoder design is coherent and stated as rules**
   ([H264Decoder.kt:12-26](../../app/src/main/kotlin/dev/openhelm/app/video/H264Decoder.kt)):
   render-on-arrival, a two-AU queue cap with IDR resync, low-latency + realtime codec keys — each
   with the reasoning attached. The same latency philosophy is carried consistently through the
   depacketizer's no-reorder-buffer policy and `RrcClient`'s drop-stale-frames queue.
4. **Gesture correctness around auto-repeat.** Every press path guarantees UP after DOWN via
   `finally` — including cancellation by edge-swipe or decomposition — with the dial's comment
   explaining the runaway-cursor failure that motivates it
   ([Dial.kt:383-412](../../app/src/main/kotlin/dev/openhelm/app/ui/Dial.kt)); the pinch-grace
   deferral in [VideoGestures.kt](../../app/src/main/kotlin/dev/openhelm/app/ui/VideoGestures.kt)
   ensures a pinch can never leak a tap to the chartplotter, and the same single implementation is
   reused by the simulator so what simulation exercises is what ships.
5. **The safety treatment of stale video** ([VideoPane.kt:182-241](../../app/src/main/kotlin/dev/openhelm/app/ui/VideoPane.kt))
   is genuinely thought through: the scrim consumes touches so a tap cannot reach the forwarding
   layer beneath, the `everStreamed` latch distinguishes loading from stale, and the overlay is
   deliberately not a Snackbar. (C1 is severe precisely because the rest of this design is so
   good.)
6. **Testable-by-construction layout math.** `keySizeFor`, `panelMetricsFor`,
   `heightIsTheLimit`, and `directionAt` are pure functions tested at window shapes "no emulator
   conveniently produces", each test citing the real defect it pins
   ([LayoutMathTest.kt](../../app/src/test/kotlin/dev/openhelm/app/ui/LayoutMathTest.kt)); the
   `PanelLayoutTest` makes dropping a control from the hand-written layout a build failure.
7. **Computed WCAG contrast as a unit test** ([PaletteContrastTest.kt](../../app/src/test/kotlin/dev/openhelm/app/ui/PaletteContrastTest.kt)),
   with the night palette held to an explicit reduced floor rather than quietly excluded — and
   ordering invariants ("night is dimmest") so palette cycling can never brighten unexpectedly.
8. **Accessibility on custom-drawn controls**: the dial exposes its five commands as custom
   actions with a tap collapsing press/release (because a screen-reader user cannot express
   "hold"), keys declare roles and spoken descriptions, and selection states are never carried by
   colour alone.
9. **Comment culture.** `AndroidManifest.xml` justifies each permission inline; a fixed inset
   documents why it isn't read from `RoundedCorner`; the debug-only transport picker explains the
   upgrade-in-place hazard that made it read the preference only in debug builds
   ([MainViewModel.kt:153-163](../../app/src/main/kotlin/dev/openhelm/app/ui/MainViewModel.kt)).
   The C2/H-class bugs above were findable *because* the comments state intent precisely enough to
   diff against behaviour.
10. **Build hygiene basics are right**: minimal permission set, single exported component (the
    launcher activity, as required), no cleartext-HTTP exposure (raw sockets by design, guarded by
    `LocalAddresses`), `local.properties` correctly ignored, pure-JVM protocol module keeping the
    wire format verifiable without an emulator.

---

## 6b. Post-fix verification (added 2026-07-28, after the fixes landed)

Every finding above was fixed, and the connection/video plumbing was then exercised against the
MFD emulator on an AVD (OpenHelmTab35, API 35, debug build) using a new fault-injection surface
added for the purpose — see [`../../tests/16-resilience-faults.md`](../../tests/16-resilience-faults.md)
for the full method, evidence and screenshots, and the emulator README for the fault list.

| Scenario | Result |
|---|---|
| Transient control drop (socket closed, listener up) | **PASS** — reconnected on the first backoff (~1 s); controls dimmed and inert during the gap; video unaffected |
| Control refused (listener down) | **PASS** — key taps sent nothing (verified against a positive control); gave up after ~5 attempts and returned to the connect screen with `Connection lost` + reason |
| Recovery | **PASS** — reconnected ~3 s after the display returned |
| Video only (stream starved) | **PASS** — `VIDEO DELAYED — NOT LIVE` over the still-visible last frame; session stayed `Connected`; keys kept working; video recovered on its own |
| Whole network down, then up | **PASS** — both halves degraded together, gave up to the connect screen, then fully restored |
| Hung display (socket open, reads stalled) | **Limitation, documented** — the app correctly reports `Connected`; queued frames arrive late in a burst. The write watchdog only fires once a write actually blocks, which a low-rate control channel reaches slowly. Inherent to a protocol with no heartbeat; two mitigation options are recorded in the test file. |

Across the whole run one app process survived every fault (9 min uptime) with **zero**
`FATAL EXCEPTION`, ANR, or process-death entries in logcat — the only app-tagged output was the
retry loop's own deliberate `W OpenHelm: Connection attempt failed`. That is the C1/C2 contract
(no failure kills a loop; every failure is loud but survivable) demonstrated rather than asserted.

## 7. Recommended next steps

> **Status:** items 1–4 below are **done** as of 2026-07-28 and verified in §6b; 5–7 remain.

1. ~~**Fix C1 and C2 together**~~ — **done**. Decoder errors now tear the pipeline down, both
   loops catch `Exception` (rethrowing `CancellationException`), and ports are validated at
   `MfdEndpoint.parse` *and* `EndpointStore.decode` with tests at both.
2. ~~**Then the H-cluster in `RrcClient`/`VideoPlayer`/`WifiNetworkBinder`** (H1–H4)~~ — **done**,
   and soak-tested against the emulator on an AVD; see §6b. The half-open case is covered by the
   new `stall-on` fault and is recorded as a known limitation rather than a fix.
3. ~~**Schedule M1 (RTP source check)**~~ — **done**: datagrams whose source is not the negotiated
   display are dropped.
4. ~~**Discovery robustness (M2)**~~ — **done**: `start()` now runs a full idempotent teardown
   first, resolves are bounded at 5 s, and a start failure releases the multicast lock.
5. **Testability debt (M6, M7) opportunistically**: interface the ViewModel's collaborators when
   the connection plumbing is next opened (step 2 naturally introduces seams), and give
   `RtspSession` streams instead of a `Socket` the next time it is touched.
6. **Before the public split**: L1 (backup rules), L2 (R8), L3 (version catalog + dependency
   automation), and a KDoc note for L9 so the standalone repo explains its own decoder half.
7. **Keep the review cadence.** The design review (2026-07-26) and this code review found almost
   disjoint defect sets — visual/UX defects there, lifecycle/concurrency defects here — which is
   evidence both lenses are earning their keep. A focused re-review of just the connection
   plumbing after step 2 lands would close the loop on the highest-risk code in the app.
