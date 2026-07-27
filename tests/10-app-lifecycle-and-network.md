# 10 — App lifecycle, rotation, and network binding

Android-level behaviour: surviving rotation and backgrounding, and the network binding that decides
whether the app can reach the display at all when mobile data is up.

> Read [README.md](README.md) first.

**Rig:** A or B for lifecycle; **C (or a phone with a real SIM) for the cellular test**, which is the
one that matters most and cannot be faked.

---

### 10.1 Rotation does not drop the session

- **SETUP:** Connected, video rendering.
- **STEPS:**
  ```bash
  "$ADB" shell settings put system accelerometer_rotation 0
  "$ADB" logcat -c
  "$ADB" shell settings put system user_rotation 0   # portrait
  sleep 4; "$ADB" exec-out screencap -p > 10_1_portrait.png
  "$ADB" shell settings put system user_rotation 1   # landscape
  sleep 4; "$ADB" exec-out screencap -p > 10_1_landscape.png
  tail -5 emulator/emu.log
  ```
- **EXPECTED:** The control connection survives both rotations — the connection machinery outlives
  any one screen, so rotating the phone must not reconnect. Video resumes (the surface is recreated,
  so a brief re-establish is acceptable). Landscape is the primary orientation.
- **VERIFY:** `emu.log` shows **no** new `client connected` line caused by rotation. Both
  screenshots show a working session.
- **PASS/FAIL:** PASS if the control session persists across rotation. FAIL if it reconnects or the
  app returns to the connect screen.

---

### 10.2 Background and resume

- **SETUP:** Connected, video rendering.
- **STEPS:**
  ```bash
  "$ADB" shell input keyevent KEYCODE_HOME
  sleep 10
  "$ADB" shell am start -n $ACT
  sleep 8
  "$ADB" exec-out screencap -p > 10_2_resumed.png
  ```
- **EXPECTED:** On resume the app shows a working session again — either still connected, or
  reconnected automatically. Video renders again. No crash.
- **VERIFY:** Screenshot shows the remote with picture.
- **PASS/FAIL:** PASS if resuming returns to a working session without user action. FAIL on a crash,
  a black permanent video pane, or being dumped back to discovery.

> **Known gap:** the session is **not** currently kept alive by a foreground service, so a long
> background period may end it. That is planned work (phase 1b), not a defect of this build — record
> which behaviour you observe.

---

### 10.3 Screen stays awake while video is showing

- **SETUP:** Connected with video, device display timeout set short.
  ```bash
  "$ADB" shell settings put system screen_off_timeout 15000
  ```
- **STEPS:** Leave the app untouched with video rendering for 40 seconds.
- **EXPECTED:** The screen stays on while video is showing (this is a device mounted at a helm).
  With video off, normal timeout behaviour resumes so battery still matters.
- **VERIFY:** The display is still on after 40 s; after switching video off it sleeps normally.
- **PASS/FAIL:** PASS if video keeps the screen awake and control-only does not. FAIL if the screen
  sleeps mid-video. Restore the timeout afterwards.

---

### 10.4 Wi-Fi binding with mobile data ON — the one that matters

The MFD's access point has **no internet behind it**. With mobile data up, Android routes new
sockets over cellular by default and the display is simply unreachable, with nothing but connect
timeouts to show for it. The app must pin the process to the Wi-Fi network before **any** socket
opens, on **every** connect path including retries.

- **SETUP:** **Rig C** (or rig B on a phone with a working SIM). Phone joined to the display's
  Wi-Fi, and **mobile data explicitly enabled**.
  ```bash
  "$ADB" -s <serial> shell svc data enable
  "$ADB" -s <serial> shell dumpsys connectivity | grep -iE "NetworkAgentInfo.*(WIFI|CELLULAR)" | head
  ```
- **STEPS:** Launch the app and let it connect. Then disconnect and reconnect (the retry path is the
  one that historically regressed).
- **EXPECTED:** Discovery, control and video all work with cellular active. The binding is
  re-asserted on the reconnect, not only on the first attempt.
- **VERIFY:** The app connects and video renders with mobile data on. Confirm both networks are up
  in the connectivity dump.
- **PASS/FAIL:** PASS if everything works with cellular enabled, first connect **and** reconnect.
  FAIL if it works only with mobile data off — the process binding is missing or not re-applied.
  **BLOCKED** on an emulator with no cellular.

---

### 10.5 The app never gates on SSID or BSSID

- **SETUP:** Any.
- **STEPS:**
  ```bash
  grep -rniE "getBSSID|getSSID|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION" openhelm/app/src/main/
  "$ADB" shell dumpsys package $PKG | grep -i location
  ```
- **EXPECTED:** No matches. These values are location-redacted on modern Android and return
  placeholders without location permission, so any gate on them can never pass. The app identifies
  displays by what they advertise and by connecting, never by network name.
- **VERIFY:** Both greps are empty.
- **PASS/FAIL:** PASS if there is no SSID/BSSID gate and no location permission. FAIL on any match.

---

### 10.6 Cleartext traffic is permitted for the display

RTSP and the control channel are plaintext by nature.

- **SETUP:** Any.
- **STEPS:** Connect and confirm video and control both work; check the manifest.
  ```bash
  grep -n "usesCleartextTraffic\|networkSecurityConfig" openhelm/app/src/main/AndroidManifest.xml
  ```
- **EXPECTED:** Cleartext to the display works. If a policy is declared it must not block the
  display's plain RTSP/RRC.
- **VERIFY:** A working video + control session is itself the proof.
- **PASS/FAIL:** PASS if plaintext traffic to the display works. FAIL if RTSP is blocked by policy.

---

### 10.7 Predictive back is enabled

- **SETUP:** Any.
- **STEPS:**
  ```bash
  grep -n "enableOnBackInvokedCallback" openhelm/app/src/main/AndroidManifest.xml
  ```
  Then, on device, begin a back gesture from the screen edge and hold partway.
- **EXPECTED:** The manifest opts in, and the back gesture shows the platform's predictive preview
  rather than snapping.
- **VERIFY:** The grep matches `true`; the gesture previews.
- **PASS/FAIL:** PASS if declared and the app's back navigation behaves. FAIL if back exits the app
  from a nested screen.

---

### 10.8 Back stack behaves from every screen

- **SETUP:** Each of: manual connect, settings, simulated remote (side-by-side and full-screen),
  full-screen remote, side-by-side.
- **STEPS:** From each, press system Back once and record where you land.
- **EXPECTED:**
  | From | Back goes to |
  |---|---|
  | Manual connect | Connect screen (no connection attempted) |
  | Settings | Connect screen |
  | Simulated remote, side-by-side | Connect screen (exits simulation) |
  | Simulated remote, full-screen | Simulated remote, side-by-side |
  | Full-screen remote | Side-by-side |
  | Side-by-side | Disconnect → connect screen |
  | Connect screen | Leaves the app normally (the only place that should) |
- **VERIFY:** Each transition matches. No dialog closes the app on an outside tap.
- **PASS/FAIL:** PASS if the table holds. FAIL if Back exits the app from a nested screen or strands
  the user.

---

### 10.9 No leaks across a long session

- **SETUP:** Connected with video.
- **STEPS:** Leave it running 10 minutes, then sample.
  ```bash
  PID=$("$ADB" shell pidof $PKG | tr -d '\r')
  "$ADB" shell dumpsys meminfo $PKG | head -20
  "$ADB" shell cat /proc/$PID/status | grep -i Threads
  ```
  Compare against the same sample taken a minute after connecting.
- **EXPECTED:** Memory is stable, thread count flat. Nothing grows monotonically.
- **VERIFY:** The two samples are comparable.
- **PASS/FAIL:** PASS if stable. FAIL on steady growth in either — a decode-buffer or socket leak.
