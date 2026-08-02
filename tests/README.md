# OpenHelm — validation test suite

Feature-by-feature test instructions for OpenHelm, written so a person **or** an agent driving
`adb` can validate the app end to end and get an objective pass/fail for every behaviour.

Each `NN-*.md` file is one feature area and is self-contained: **SETUP → STEPS → EXPECTED →
VERIFY → PASS/FAIL**. Run them in numeric order for a full pass, or individually. **This README is
the shared harness every test file depends on — read it first.**

| File | Area |
|---|---|
| [01-build-install-launch.md](01-build-install-launch.md) | Gradle build, unit tests, install, first launch, no crash |
| [02-connect-screen.md](02-connect-screen.md) | Scanning state, recent buttons, overflow menu, layout |
| [03-manual-connect.md](03-manual-connect.md) | Address entry, bare-IP defaults, transport dropdown |
| [04-discovery-and-autoconnect.md](04-discovery-and-autoconnect.md) | mDNS discovery, recents-first probe, auto-connect, no-display path |
| [05-remembered-displays.md](05-remembered-displays.md) | Persistence, naming, forgetting, ordering, label fallback |
| [06-control-channel.md](06-control-channel.md) | RRC framing: every key, press/release timing, hold, zoom |
| [07-video-pipeline.md](07-video-pipeline.md) | RTSP/RTP → MediaCodec, letterboxing, overlay, control-only mode |
| [08-touch-and-gestures.md](08-touch-and-gestures.md) | Opcode-3 touch, drag, pinch/pan local zoom, mode switch |
| [09-connection-lifecycle.md](09-connection-lifecycle.md) | Disconnect, reconnect, backoff, drop recovery, watchdog |
| [10-app-lifecycle-and-network.md](10-app-lifecycle-and-network.md) | Rotation, background/resume, Wi-Fi binding, back stack |
| [11-latency.md](11-latency.md) | Glass-to-glass latency — **real phone only** |
| [12-clean-room-and-provenance.md](12-clean-room-and-provenance.md) | Clean-room greps, subtree isolation, licence |
| [13-simulation-mode.md](13-simulation-mode.md) | Settings' Simulation mode: fake video, live controls, non-persistence |
| [14-design-review-fixes.md](14-design-review-fixes.md) | The design review's P0s: adaptive panel, touch targets, stale-video, vocabulary, icons |
| [15-council-fixes.md](15-council-fixes.md) | The code review's findings: pinch leakage, gesture cancellation, scrim touches, the palettes, accessibility |
| [16-resilience-faults.md](16-resilience-faults.md) | **Resilience 1/5** — the outage matrix: transient drop, refused control, video-only loss, whole-network down; retry, give-up-to-scanning, greyed controls, delayed-video warning |
| [17-video-resilience.md](17-video-resilience.md) | **Resilience 2/5** — video pipeline: starved stream vs refused endpoint, first-frame latch, remote-only refuge, flapping, decoder reclaim |
| [18-discovery-resilience.md](18-discovery-resilience.md) | **Resilience 3/5** — discovery: window elapses honestly, mid-scan recovery, mDNS flapping, repeated Scan-again, probe-before-scan order |
| [19-resilience-lifecycle.md](19-resilience-lifecycle.md) | **Resilience 4/5** — failures crossed with lifecycle: backgrounded/rotated mid-reconnect, connection dies mid-hold, Disconnect mid-reconnect, cold start into a dead display |
| [20-resilience-soak.md](20-resilience-soak.md) | **Resilience 5/5** — soak and resource health: flap ×30/×20, full-outage cycling, fd/thread/memory comparison, long unattended run |

---

## 0. Architecture: ARM is the target

**OpenHelm ships to ARM phones and tablets. `arm64-v8a` is the architecture that counts.**

An x86_64 emulator is a convenience, not a verification target. Use it freely for UI work,
navigation, protocol framing and state machines — all of which are architecture-independent — but
**a release is not verified until it has run on real ARM hardware.**

The reason is specific and it is the video path: `MediaCodec` on an x86_64 emulator resolves to a
software/emulated decoder, whereas an ARM phone uses the vendor's hardware decoder. Low-latency
decode, codec priority, output-buffer timing and colour formats can all differ, and latency is this
project's headline claim. A pipeline that looks right under a software decoder can still miss its
budget on the hardware that matters.

> ⚠️ **There is no local `arm64` AVD path on a Windows/x86_64 host, full stop — not merely a slow
> one.** This was checked directly: current emulator releases (36.5.11 / 36.6.11, the only ones this
> SDK channel offers) refuse outright to boot an `arm64-v8a` system image on an x86_64 host —
> `FATAL: Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host.
> System image must match the host architecture.` Older emulator releases had ARM-on-x86 support via
> software (TCG) instruction translation, but it is gone from the versions available here, and even
> where it existed it was reportedly slow enough (order tens of times real time) to be impractical
> for a video-decode-heavy app like this one. **Do not spend time chasing an arm64 AVD on this
> machine** — on an Apple Silicon Mac the image would run *natively* and this limitation would not
> apply, but that is not this host. The only way to satisfy an **arm64**-tagged test here is a real
> device.

Check what you are actually running on before trusting any result:

```bash
"$ADB" shell getprop ro.product.cpu.abi          # expect arm64-v8a for a real verification run
"$ADB" shell getprop ro.product.cpu.abilist
```

Every test below is tagged with an **Arch** requirement:

| Tag | Meaning |
|---|---|
| **any** | Architecture-independent — x86_64 emulator is fine. |
| **arm64** | Must run where `ro.product.cpu.abi` reports `arm64-v8a` to count — a real device, or an AVD on a host where one can actually boot (not this machine; see above). |
| **device** | Must be real ARM hardware; no emulator result is meaningful even where arm64 AVDs work. |

If you have only an x86_64 emulator, an **arm64**-tagged test is **BLOCKED**, not PASS.

---

## 1. Choose a rig — this decides which tests can run

Three rigs. **Every test file names which rig(s) it needs**, and a test that needs a rig you do not
have is **BLOCKED**, not FAIL.

### Rig A — AVD + simulated display (most tests, no hardware)

The Android emulator plus the MFD simulator running on the same host. Covers everything except
video transport realism and latency.

**On a host where an arm64 AVD actually boots** (confirmed not this Windows/x86_64 machine — see §0
— but plausible on Apple Silicon, or an x86_64 host with an older emulator package that still carries
TCG ARM support), preferring it exercises the shipping architecture:

```bash
SDK="$LOCALAPPDATA/Android/Sdk"    # or $HOME/Library/Android/sdk on macOS
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install "system-images;android-36-ext19;google_apis;arm64-v8a"
"$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n OpenHelmArm64 \
    -k "system-images;android-36-ext19;google_apis;arm64-v8a" -d pixel_6
"$SDK/emulator/emulator" -avd OpenHelmArm64 -no-boot-anim &
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do sleep 3; done
"$ADB" shell getprop ro.product.cpu.abi        # confirm arm64-v8a before trusting the run
```

**On this project's Windows/x86_64 dev machine, skip straight to an x86_64 AVD** for everything
tagged **any**, and treat every **arm64**-tagged test as BLOCKED until rig B or C is available:

```bash
# 1. Start the simulator (host). --source clock burns a frame counter into every frame.
cd emulator
python -m mfd_emulator --no-console --video-mode hls --source clock --log-file emu.log

# 2. Start one AVD, and only one.
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd SeaWhisperTab -no-boot-anim &
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do sleep 3; done
```

Then in the app: overflow menu (top-right) → **Manual connect** → address
`10.0.2.2:8555:50000:RAYMARINEMFD:10` → transport **TCP (testing only)** → Connect.

> **Why manual, and why TCP.** The AVD's SLIRP NAT carries neither mDNS multicast (so discovery
> cannot find the simulator) nor inbound RTP over UDP (so video cannot arrive). `10.0.2.2` is the
> host as seen from the guest. TCP interleaving is a **simulator-only** deviation that exists to
> make the decode path testable here; a real display accepts it and then never sends a frame.

### Rig B — real phone + simulated display (transport-realistic, no boat)

An Android phone on the same Wi-Fi as the host running the simulator. mDNS works, UDP works.

```bash
cd emulator
python -m mfd_emulator --no-console --source clock --log-file emu.log   # default rtsp/UDP mode
adb devices          # note the phone's serial; use adb -s <serial> throughout
```

In the app: let it scan (discovery should find the simulator), or overflow menu → **Manual
connect** → `<host-LAN-IP>` → transport **UDP**.

> Windows: the Wi-Fi profile must be **Private** or mDNS is blocked outright, and inbound rules are
> needed for TCP 8555/50000 and UDP 5353.

### Rig C — real phone + real MFD (the boat)

The phone joined to the MFD's own Wi-Fi access point. The only rig that proves real behaviour.

```bash
cd emulator
python scripts/find_mfd.py          # prints the MFD's address, TXT records and RTSP URL
```

Reference device: a Raymarine **e95** (HybridTouch), advertising `raymarine-mfd-model=E9` — note
that string is abbreviated and does **not** identify the hardware; the serial's part number
(`E70021`) does.

### What each rig can prove

| | Rig A/x86_64 | Rig A/arm64 | Rig B (phone + sim) | Rig C (boat) |
|---|---|---|---|---|
| Build, install, launch, UI, navigation | ✅ | ✅ | ✅ | ✅ |
| Control channel framing | ✅ | ✅ | ✅ | ✅ |
| **Shipping architecture exercised** | ❌ | ✅ | ✅ | ✅ |
| **Hardware video decode** | ❌ software codec | ⚠️ emulated | ✅ | ✅ |
| mDNS discovery | ❌ NAT blocks multicast | ❌ same | ✅ | ✅ |
| Video renders | ✅ over TCP only | ✅ over TCP only | ✅ over UDP | ✅ |
| RTP-over-UDP transport | ❌ | ❌ | ✅ | ✅ |
| **Latency** | ❌ **never** | ❌ **never** | ⚠️ indicative | ✅ authoritative |
| Timing-sensitive behaviour (holds) | ⚠️ | ⚠️ slow under emulation | ✅ | ✅ |
| What the display actually *does* | ❌ | ❌ | ❌ | ✅ |

> ⚠️ **Never judge latency on an AVD.** Its only working video path is segment-buffered and sits
> seconds behind live by construction — the same range as the bug this app exists to fix, so it
> will "confirm" a regression that is really the rig. See [11-latency.md](11-latency.md).

---

## 2. Shared variables

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
EMU="$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe"
PKG=dev.openhelm.app
ACT=$PKG/.MainActivity
APK=openhelm/app/build/outputs/apk/debug/app-debug.apk
SIM=10.0.2.2:8555:50000:RAYMARINEMFD:10     # rig A manual address
```

For a physical device add `-s <serial>` to every `adb` call, and **always** when an AVD is also
running.

---

## 3. Common commands

```bash
# Build + unit tests
cd openhelm && ./gradlew :protocol:test :app:assembleDebug

# Install — ALWAYS assert on "Success". adb reports failure on stderr, so a pipeline that keeps
# only the last stdout line makes a rejected install look identical to a good one.
out=$("$ADB" install -r "$APK" 2>&1); echo "$out" | grep -q Success && echo "INSTALL OK" || echo "INSTALL FAILED: $out"

# Reset to first-run state (clears remembered displays and preferences)
"$ADB" shell pm clear $PKG

# Launch / stop
"$ADB" shell am start -n $ACT
"$ADB" shell am force-stop $PKG

# Screenshot — use exec-out; a plain `screencap /sdcard/x.png` path gets mangled by Git Bash
"$ADB" exec-out screencap -p > shot.png

# Which app is actually in front  ← see §4, this matters
"$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"

# UI tree, for finding a control's bounds instead of hard-coding pixels
"$ADB" exec-out uiautomator dump /dev/tty

# Input (screen is 2560x1600 landscape on SeaWhisperTab; screenshot px == device px)
"$ADB" shell input tap <x> <y>
"$ADB" shell input swipe <x1> <y1> <x2> <y2> <ms>     # a long same-point swipe = a HOLD
"$ADB" shell input text 'some text'
"$ADB" shell input keyevent 4                          # BACK

# Crash check
"$ADB" logcat -d AndroidRuntime:E *:F | tail -40

# Simulator's decoded control log (the oracle for every control test)
tail -f emulator/emu.log
```

### Fault injection — the harness the resilience tests (16–20) run on

The simulator can take itself apart in the specific ways a boat does. Faults are fired from a
script, so a test can drive a failure and assert on the app's response without a human at the
keyboard:

```bash
cd emulator
python -m mfd_emulator --no-console --log-file emu.log     # control port up on 127.0.0.1:8571
python scripts/fault.py status                             # what is currently up
python scripts/fault.py log 30                             # tail the event log
```

| Command | What it does to the simulated display |
|---|---|
| `close-rrc` | closes the control socket; the listener stays up → **transient** drop, next reconnect succeeds |
| `rrc-down` / `rrc-up` | stops/resumes the control listener → every reconnect **refused** (the display left the network) |
| `stall-on` / `stall-off` | stops reading without closing → **hung** display: socket ESTABLISHED, frames ignored |
| `stream-drop` / `stream-resume` | kills/restarts FFmpeg, RTSP endpoint stays up → the session **starves** |
| `video-down` / `video-up` | stops/starts the whole RTSP endpoint → video **refused**, control unaffected |
| `net-down` / `net-up` | advertising + control + video, all at once → the display **vanishes** |
| `discovery-toggle` | starts/stops advertising `_rtsp._tcp` |

The app-side constants these tests assert against, so an expectation and the code cannot drift
apart silently:

| Constant | Value | Where |
|---|---|---|
| Control retry budget | 5 attempts, backoff 1/2/4/8/15 s (~30 s) then **give up → connect screen** | `RrcClient.MAX_ATTEMPTS` |
| Control connect timeout | 45 s first attempt, 20 s on retries | `RrcClient.CONNECT_TIMEOUT_MS` |
| Write stall watchdog | 5 s | `RrcClient.WRITE_STALL_MS` |
| Video retry | **unbounded**, backoff 3 s → 15 s | `VideoPlayer.RETRY_BACKOFF_MS` |
| Video stall timeout | 10 s with no RTP | `VideoPlayer.RTP_STALL_TIMEOUT_MS` |
| RTSP response timeout | 15 s | `VideoPlayer.RTSP_RESPONSE_TIMEOUT_MS` |
| Discovery window | 12 s | `MainViewModel.DISCOVERY_WINDOW_MS` |

> **The asymmetry is deliberate and is what several of these tests assert:** losing *video*
> degrades the session to remote-only and retries forever; losing *control* ends the session and
> hands the user back to the scanning screen. Video is a convenience, control is the product.

### Tap a control by its label rather than by pixel

Layouts change; derived coordinates do not go stale.

```bash
tap_text () {                       # usage: tap_text "Manual connect"
  "$ADB" exec-out uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  local b
  b=$("$ADB" exec-out cat /sdcard/u.xml | tr '>' '\n' | grep "text=\"$1\"" \
      | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  [ -z "$b" ] && { echo "NOT FOUND: $1"; return 1; }
  local n; n=$(echo "$b" | grep -o '[0-9]\+')
  local x1 y1 x2 y2; read x1 y1 x2 y2 <<<"$(echo $n)"
  "$ADB" shell input tap $(((x1+x2)/2)) $(((y1+y2)/2))
}
```

---

## 4. Two traps that have produced confidently wrong results here

**Confirm which app is in front before you believe a screenshot.** This machine has both OpenHelm
and the old vendor APK installed, and other work may be driving the same emulator. A screenshot of
someone else's app looks like a failure of yours. Before interpreting any screenshot:

```bash
"$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"   # must name dev.openhelm.app
```

If more than one emulator is running, `adb` commands without `-s` are ambiguous — stop all but one.

**Never conclude from an absence of events.** A system dialog (ANR, permission, crash) silently
swallows injected taps and shows nothing in logcat, so "no frame arrived" can mean "the tap never
reached the app". Screenshot before concluding the app is at fault.

---

## 5. Conventions in each test file

- **SETUP** — the rig and the exact state required before starting.
- **STEPS** — numbered, with commands to run.
- **EXPECTED** — what should happen.
- **VERIFY** — the concrete check: a screenshot to look at, a log line, a UI-dump string.
- **PASS/FAIL** — objective criteria. **BLOCKED** when the rig cannot exercise the step.
- Coordinates assume the 2560×1600 landscape AVD. Prefer `tap_text`; re-derive from a fresh
  screenshot when a layout differs.
- `adb logcat -c` immediately before any action you will verify from logs.
