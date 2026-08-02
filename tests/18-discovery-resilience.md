# 18 — Discovery resilience

**Tag:** any for the state-machine behaviour; **device**/rig B for anything that needs real mDNS —
the AVD's SLIRP NAT does not carry multicast, so on rig A discovery can only ever *fail*, and
failing correctly is exactly what half this file tests.
**Rig:** A for D1/D4/D5 (failure paths), B or C for D2/D3/D6 (success paths).

mDNS on boat Wi-Fi is unreliable — that is a finding from the water, not an opinion, and it is why
manual entry is a peer of discovery rather than a fallback. This file tests what the app does when
discovery fails, flaps, or recovers, and the resource hygiene underneath it.

The specific regression D4 guards is worth stating: `MfdDiscovery.start()` used to leave debris
behind when the platform refused to start a browse — one registered listener of the pair, a live
resolve worker, a held multicast lock — and the `_searching` flag alone did not see it. Every
"Scan again" after a failure leaked another listener into `NsdManager` until its per-app limit
broke discovery for the life of the process. On a boat, "Scan again" is precisely the button a
user presses repeatedly.

---

## SETUP

Discovery **on** (no `--no-discovery`), so advertising can be toggled:

```bash
cd emulator
python -m mfd_emulator --no-console --log-file emu.log
python scripts/fault.py status        # "_rtsp._tcp advertised : True"
```

Start each case from a clean slate where noted: `"$ADB" shell pm clear $PKG`.

---

## D1 — Nothing to find: the discovery window elapses honestly

**STEPS**

```bash
"$ADB" shell pm clear $PKG                  # no recents, so nothing to probe first
python scripts/fault.py discovery-toggle    # advertising OFF
"$ADB" shell am start -n $ACT
```

**EXPECTED**

1. The ring animates `Scanning` for the full window (12 s, `DISCOVERY_WINDOW_MS`).
2. Then the ring **becomes the button**: it fills, reads `Scan again`, and `No MFD found` appears
   underneath with `Some boat networks block automatic discovery.`
3. No spinner runs forever, and no modal appears — the app must never trap the user in a dialog.

**VERIFY**

```bash
sleep 15
"$ADB" exec-out uiautomator dump /dev/tty | tr '>' '\n' | grep -o 'text="[^"]*"' \
  | grep -Ei "scan again|no mfd|scanning"
```

**PASS** timed out to `Scan again` + `No MFD found` within ~15 s. **FAIL** on an endless spinner, a
dialog, or a crash.

---

## D2 — Discovery recovers mid-scan → auto-connect — **rig B/C**

**STEPS** with advertising off and a scan running, turn it back on inside the window:

```bash
python scripts/fault.py discovery-toggle    # advertising ON, while "Scanning" is showing
```

**EXPECTED** the display is found and the app **connects on its own** — these networks carry
exactly one display, so presenting a chooser would be ceremony. No tap required.

**VERIFY** `fault.py log 10` shows an RRC client connecting; UI reaches `Connected · <host>`.

**PASS** auto-connected unattended. **BLOCKED** on rig A (no multicast).

---

## D3 — mDNS flapping must not evict a good entry — **rig B/C**

Adverts drop and return constantly on weak Wi-Fi. `onServiceLost` is deliberately ignored: losing
an advert does not mean the device is gone, and connecting is what actually settles the question.

**STEPS** with a display discovered but **not** yet connected, toggle advertising off and on 5×
with ~3 s between, then connect.

**EXPECTED** the entry does not vanish and reappear from the UI on each flap; connecting still
works afterwards.

**PASS** stable list, connect succeeds. **BLOCKED** on rig A.

---

## D4 — "Scan again" after a failed start, ×10 (the leaked-listener regression)

The failure this guards cannot be produced on demand from the simulator — `onStartDiscoveryFailed`
comes from the platform. What *can* be tested is that repeated start/stop cycling stays healthy,
which is the same code path (`start()` now runs a full idempotent `stop()` first).

**STEPS**

```bash
python scripts/fault.py discovery-toggle     # advertising OFF, so every scan times out
for i in $(seq 1 10); do
  tap_text "Scan again"
  sleep 14                                   # let the 12 s window elapse
done
```

**EXPECTED** the tenth scan behaves exactly like the first: ring animates, times out, offers
`Scan again`. No degradation, no scan that never starts, no crash.

**VERIFY**

```bash
"$ADB" logcat -d | grep -iE "nsd|discovery" | tail -30      # no "too many listeners"/registration errors
"$ADB" logcat -d AndroidRuntime:E *:F | tail -20            # expect nothing
"$ADB" shell dumpsys activity services | grep -ci nsd       # sanity: no runaway growth
```

Then confirm discovery still **works** afterwards (rig B/C): turn advertising on and scan once
more — it must find the display. A leak's symptom is that scans silently stop finding anything.

**PASS** 10/10 healthy and discovery still functional after. **FAIL** on any scan that fails to
start, NSD registration errors, or a crash.

---

## D5 — Recents are probed before the network is swept, and only local ones

At launch the app tries remembered displays first (fastest route to the display actually aboard),
then falls back to a scan. Only **numeric local** addresses are probed unprompted — a remembered
entry can have come from an mDNS advertisement, and this path runs at every launch with no user
action, so an entry pointing off-network would be an unattended connection attempt to an arbitrary
host (see `LocalAddresses`).

**STEPS**

1. Connect once to `10.0.2.2` so it is remembered, then disconnect.
2. `python scripts/fault.py rrc-down` (recents will be refused).
3. Force-stop and relaunch the app.

**EXPECTED**

1. The probe runs first — the connect screen shows the scanning state immediately.
2. `10.0.2.2` is refused, so the app falls through to a network scan rather than hanging on it.
3. The whole sequence is bounded: probe (3 s per address) then the 12 s window, never an
   indefinite wait.

**VERIFY** `fault.py log 20` shows a connection attempt to the RRC port at launch (before any
scan); UI reaches `No MFD found` / `Scan again` within ~20 s.

**PASS** probe-then-scan order observed and bounded. **FAIL** if launch hangs, or if no probe is
attempted at all.

**Off-network entries** cannot be injected through the simulator (it only ever advertises a local
address). The rule is covered exhaustively by `LocalAddressesTest` on the JVM — 30+ cases including
octal-looking octets, `.local` names, CGN space and IPv4-mapped IPv6 — which is the better place
for it anyway.

---

## D6 — Discovery survives the display disappearing entirely — **rig B/C**

**STEPS** connected and streaming, then `python scripts/fault.py net-down`; wait for the app to
give up and land on the connect screen; then `python scripts/fault.py net-up`.

**EXPECTED** after `net-up`, a scan (automatic on the connect screen, or via `Scan again`) finds
the display again and auto-connects. Discovery must not have been poisoned by the outage.

**VERIFY** UI reaches `Connected` again with no manual address entry.

**PASS** rediscovered and reconnected. **BLOCKED** on rig A (use the recents button there instead —
covered by [16](16-resilience-faults.md) S5).

---

## PASS/FAIL — file summary

| ID | Requires | Status |
|---|---|---|
| D1 window elapses honestly | A/B/C | not yet executed |
| D2 recovers mid-scan | B/C | **BLOCKED** on rig A |
| D3 flapping | B/C | **BLOCKED** on rig A |
| D4 scan-again ×10 | A/B/C | not yet executed |
| D5 recents probed first, bounded | A/B/C | not yet executed |
| D6 survives full outage | B/C | **BLOCKED** on rig A (rig-A equivalent passes — see 16 S5) |
