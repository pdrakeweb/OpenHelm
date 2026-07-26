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

---

## 1. Choose a rig — this decides which tests can run

Three rigs. **Every test file names which rig(s) it needs**, and a test that needs a rig you do not
have is **BLOCKED**, not FAIL.

### Rig A — AVD + simulated display (most tests, no hardware)

The Android emulator plus the MFD simulator running on the same host. Covers everything except
video transport realism and latency.

```bash
# 1. Start the simulator (host). --source clock burns a frame counter into every frame.
cd emulator
python -m mfd_emulator --no-console --video-mode hls --source clock --log-file emu.log

# 2. Start one AVD, and only one.
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd SeaWhisperTab -no-boot-anim &
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do sleep 3; done
```

Then in the app: **Manual connect** → address `10.0.2.2:8555:50000:RAYMARINEMFD:10` → transport
**TCP (testing only)** → Connect.

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

In the app: let it scan (discovery should find the simulator), or **Manual connect** →
`<host-LAN-IP>` → transport **UDP**.

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

| | Rig A (AVD) | Rig B (phone + sim) | Rig C (boat) |
|---|---|---|---|
| Build, install, launch, UI, navigation | ✅ | ✅ | ✅ |
| Control channel framing | ✅ | ✅ | ✅ |
| mDNS discovery | ❌ NAT blocks multicast | ✅ | ✅ |
| Video renders | ✅ over TCP only | ✅ over UDP | ✅ |
| RTP-over-UDP transport | ❌ | ✅ | ✅ |
| **Latency** | ❌ **never** | ⚠️ indicative | ✅ authoritative |
| What the display actually *does* | ❌ | ❌ | ✅ |

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
