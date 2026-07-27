# 01 — Build, unit tests, install, first launch

The foundation: the project builds, the pure-Kotlin protocol tests pass, the APK installs, and the
app reaches its first screen without crashing. Everything else assumes this passed.

> Read [README.md](README.md) first — it defines `$ADB`, `$PKG`, `$APK`, the rigs, and the
> install/screenshot/foreground-check commands.

**Rig:** A, B or C (no display needed for 01.1–01.5). **Arch:** any for 01.1–01.3; **arm64** for
01.4 onward — a launch that works under a software-emulated x86_64 runtime is not proof it launches
on the hardware this ships to.

---

### 01.1 Clean build and unit tests

- **SETUP:** A JDK 17 toolchain and the Android SDK. No device required.
- **STEPS:**
  ```bash
  cd openhelm
  ./gradlew :protocol:test :app:assembleDebug --rerun-tasks
  ```
- **EXPECTED:** `BUILD SUCCESSFUL`. The protocol module's tests all pass — framing for every
  opcode, keycodes, the version-byte parse, coordinate normalisation, touch sequencing, SDP
  parsing, and RTP/H.264 depacketization (single NAL, FU-A, STAP-A, loss handling).
- **VERIFY:**
  ```bash
  grep -ho 'tests="[0-9]*"\|failures="[0-9]*"' protocol/build/test-results/test/*.xml | sort | uniq -c
  ls -l app/build/outputs/apk/debug/app-debug.apk
  ```
  Every `failures=` is `0`, and the APK exists.
- **PASS/FAIL:** PASS if the build succeeds with zero test failures. FAIL on any compile error or
  failing test.

---

### 01.2 Toolchain guards (regression)

Two toolchain facts were settled painfully and will silently break the build if reintroduced.

- **SETUP:** As 01.1.
- **STEPS:**
  ```bash
  grep -n 'kotlin("android")' openhelm/app/build.gradle.kts openhelm/build.gradle.kts
  grep -n 'distributionUrl' openhelm/gradle/wrapper/gradle-wrapper.properties
  grep -n 'com.android.application' openhelm/build.gradle.kts
  ```
- **EXPECTED:** No `kotlin("android")` anywhere — AGP 9 ships built-in Kotlin and **fails outright**
  if the standalone plugin is also applied. The AGP version must be one the committed Gradle
  wrapper supports (AGP 9.3+ requires Gradle 9.5; the wrapper here is 9.4.1, so AGP is 9.2.x).
- **VERIFY:** The first grep prints nothing; 01.1 succeeded.
- **PASS/FAIL:** PASS if no standalone Kotlin Android plugin is applied and the build works.

---

### 01.3 Install

- **SETUP:** Exactly one device or emulator attached (`adb devices`).
- **STEPS:**
  ```bash
  out=$("$ADB" install -r "$APK" 2>&1)
  echo "$out" | grep -q Success && echo "INSTALL OK" || echo "INSTALL FAILED: $out"
  ```
- **EXPECTED:** `INSTALL OK`.
- **VERIFY:** The assertion above. Do **not** accept "no error printed" as success — `adb install`
  reports failure on stderr, so an unasserted pipeline makes a rejection look like a pass and every
  later test silently exercises the previously installed build.
- **PASS/FAIL:** PASS only on an explicit `Success`.

---

### 01.4 First launch is clean

- **SETUP:** Installed. Reset to first-run state so no remembered display short-circuits the screen.
- **STEPS:**
  ```bash
  "$ADB" shell pm clear $PKG
  "$ADB" logcat -c
  "$ADB" shell am start -n $ACT
  sleep 5
  "$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"
  "$ADB" logcat -d AndroidRuntime:E *:F | tail -40
  "$ADB" exec-out screencap -p > 01_4_launch.png
  ```
- **EXPECTED:** The connect screen: the word **Scanning** inside a ring, and the overflow (kebab)
  menu top-right. No recent buttons (state was cleared). No crash, no ANR.
- **VERIFY:** `topResumedActivity` names `dev.openhelm.app/.MainActivity`. The logcat dump contains
  no `FATAL`/`AndroidRuntime` lines for this package. Look at `01_4_launch.png` and confirm it is
  OpenHelm's dark screen, not another app.
- **PASS/FAIL:** PASS if OpenHelm is foreground with the scanning UI and no crash. FAIL on any
  fatal exception, or if the process dies back to the launcher.

> **Regression watch — `NetworkOnMainThreadException`.** This exact test caught a launch crash: the
> reachability probe did blocking socket connects on the caller's dispatcher, and its caller was
> UI-scoped. A launch that flashes and drops to the home screen is this class of bug; get the trace
> with `"$ADB" logcat -d | grep -A15 "beginning of crash"`.

---

### 01.5 Permissions are declared and none are prompted

- **SETUP:** Installed.
- **STEPS:**
  ```bash
  "$ADB" shell dumpsys package $PKG | grep -A20 "requested permissions"
  ```
- **EXPECTED:** `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
  `CHANGE_WIFI_MULTICAST_STATE`. All are install-time; **no runtime permission dialog appears at
  launch**. Notably there is no location permission — the app must never gate on BSSID/SSID, which
  are location-redacted and could never pass.
- **VERIFY:** The list matches. `01_4_launch.png` shows no permission dialog.
- **PASS/FAIL:** PASS if exactly those permissions are declared and launch prompts for nothing.
  FAIL if a location permission is requested or a dialog blocks first launch.

---

### 01.6 The APK carries every shipping ABI

OpenHelm targets ARM devices; the emulator slices are a convenience. A build that has quietly lost
`arm64-v8a` installs fine on every emulator here and fails on every real phone.

- **SETUP:** A built APK. **Arch:** any (this inspects the artifact, not the runtime).
- **STEPS:**
  ```bash
  BT="$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0"
  "$BT/aapt.exe" dump badging "$APK" | grep -E "^package|native-code"
  unzip -l "$APK" | grep -o 'lib/[^/]*' | sort -u
  ```
- **EXPECTED:** `native-code:` lists **`arm64-v8a`** and `armeabi-v7a` alongside the x86 slices —
  a universal APK. The only `.so` files are AndroidX's own
  (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`); the app has **no native code
  of its own**, which is the structural fix over the predecessor that was pinned to hand-built
  32-bit-only libraries.
- **VERIFY:** `arm64-v8a` present in both outputs.
- **PASS/FAIL:** PASS if `arm64-v8a` is in the APK. **FAIL if it is missing** — check for ABI splits
  or an `abiFilters` block that dropped it.

---

### 01.7 Install and launch on arm64

- **SETUP:** An `arm64-v8a` AVD (see [README.md](README.md) §1) or a real ARM device.
  **Arch:** arm64.
- **STEPS:**
  ```bash
  "$ADB" shell getprop ro.product.cpu.abi          # must print arm64-v8a
  out=$("$ADB" install -r "$APK" 2>&1); echo "$out" | grep -q Success && echo "INSTALL OK"
  "$ADB" shell pm clear $PKG
  "$ADB" logcat -c
  "$ADB" shell am start -n $ACT
  sleep 8
  "$ADB" shell "dumpsys activity activities | grep -i topResumedActivity"
  "$ADB" logcat -d AndroidRuntime:E *:F | tail -40
  "$ADB" exec-out screencap -p > 01_7_arm64.png
  ```
- **EXPECTED:** Identical behaviour to the x86_64 run: the connect screen with **Scanning** and the
  overflow menu, no crash. The ABI check confirms which runtime actually served the app.
- **VERIFY:** `ro.product.cpu.abi` is `arm64-v8a`, the app is foreground, logcat is clean, and the
  screenshot shows the connect screen.
- **PASS/FAIL:** PASS only if the ABI check says arm64 **and** the app runs. **BLOCKED** if no arm64
  runtime is available — do not substitute an x86_64 pass.

> ⚠️ **On this project's Windows/x86_64 dev machine this test is BLOCKED, not slow.** Checked
> directly: the emulator here (36.5.11 / 36.6.11) refuses outright to boot an `arm64-v8a` image on an
> x86_64 host — `FATAL: Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on
> x86_64 host.` There is no local workaround on this machine; use a real ARM device. See
> [README.md](README.md) §0 for the full finding.

---

### 01.8 Reinstall over an existing install preserves data

- **SETUP:** At least one remembered display (run [05](05-remembered-displays.md) 05.1 first).
- **STEPS:**
  ```bash
  out=$("$ADB" install -r "$APK" 2>&1); echo "$out" | grep -q Success && echo "INSTALL OK"
  "$ADB" shell am force-stop $PKG && "$ADB" shell am start -n $ACT
  sleep 4
  "$ADB" exec-out screencap -p > 01_6_reinstall.png
  ```
- **EXPECTED:** `-r` reinstalls in place; the remembered display and its name survive.
- **VERIFY:** `01_6_reinstall.png` still shows the recent button with its name.
- **PASS/FAIL:** PASS if data survives a `-r` reinstall. FAIL if recents are lost (that would mean
  the install was not actually an update — check the signing key).
