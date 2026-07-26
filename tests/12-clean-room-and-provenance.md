# 12 — Clean-room provenance and packaging

OpenHelm is intended to be split out of its parent repository and published as open source. That
parent also contains a decompilation of a vendor APK, done for interoperability research, and
**none of that material may end up here**. These checks are cheap, they are the ones that must pass
before any release, and they belong in CI once the project is extracted.

The rule: nothing in `openhelm/` may be derived from the vendor's copyrighted expression. Facts
about bytes on a wire — frame layout, opcode numbers, keycode values, service names, the coordinate
formula — are **not** copyrightable expression and are what this app is built from.

> Read [README.md](README.md) first. These tests need no device.

**Rig:** none — run from a checkout.

---

### 12.1 No vendor code, class names or branding in tracked source

- **STEPS:**
  ```bash
  cd openhelm
  git ls-files '*.kt' '*.kts' '*.xml' \
    | xargs grep -niE 'com\.raymarine|RayRemote|RayControl|smali|apktool'
  ```
- **EXPECTED:** No matches in code. Matches are permitted **only** in documentation that discusses
  provenance (`CLEAN-ROOM.md`, and prose in these test files).
- **VERIFY:** Empty output over `*.kt`/`*.kts`/`*.xml`.
- **PASS/FAIL:** PASS if empty. FAIL on any match in source — including an obfuscated class name
  (`ad`, `ao`, `RayImageButton`) copied from a decompile. Nothing is gained by reusing them.

> **One expected exception, by design.** The strings `raymarine-mfd-rtsp-path`,
> `raymarine-mfd-model`, `raymarine-mfd-serial` and `raymarine-mfd-rrc-version` appear as constants
> in `protocol/`. These are **DNS-SD TXT keys — literal bytes on the wire**. A client that does not
> send exactly those strings cannot talk to the device at all, so they are protocol facts of the
> same kind as an opcode number: not branding, not copied expression. The grep above is written not
> to flag them, deliberately.

---

### 12.2 No binary artwork of unknown origin

- **STEPS:**
  ```bash
  cd openhelm
  git ls-files '*.png' '*.jpg' '*.jpeg' '*.webp' '*.gif' | grep -v -i 'build/'
  ```
- **EXPECTED:** Empty, or only files authored by this project. Every icon and control graphic is
  drawn from scratch as Compose vector code or original vector XML — the launcher icon, the arrows,
  the dial, the kebab. No vendor button PNG is traced, recoloured or used as a reference image.
- **VERIFY:** Empty output. Spot-check that the drawables present are vector XML, not rasterised
  copies:
  ```bash
  git ls-files 'app/src/main/res/**' | xargs -I{} sh -c 'echo "--- {}"; head -3 "{}"'
  ```
- **PASS/FAIL:** PASS if no unexplained binary images are tracked. FAIL on any bitmap that cannot be
  accounted for.

---

### 12.3 Commits touching `openhelm/` touch nothing else

The subtree is extracted with `git subtree split`, so commit messages travel with it into a public
history. A commit that mixes in parent-repo work becomes a permanently wrong message.

- **STEPS:**
  ```bash
  # from the repo root, for each commit that touches openhelm/
  for c in $(git log --format=%h -- openhelm/); do
    outside=$(git show --stat --name-only --format="" $c | grep -v '^openhelm/' | grep -v '^$')
    [ -n "$outside" ] && { echo "=== $c ALSO TOUCHES:"; echo "$outside"; }
  done
  echo "check complete"
  ```
- **EXPECTED:** No commit that touches `openhelm/` also touches anything outside it.
- **VERIFY:** The loop prints only `check complete`.
- **PASS/FAIL:** PASS if isolated. FAIL listing any mixed commit. (One historical commit is known to
  have swept in another session's in-progress work via a blanket `git add` — hence the repo rule to
  stage explicit paths and read `git diff --cached --name-only` before committing.)

---

### 12.4 The subtree extracts cleanly

- **STEPS:**
  ```bash
  git subtree split --prefix=openhelm -b openhelm-only
  git log --oneline openhelm-only | head -20
  git log --oneline openhelm-only -- decompiled/ assets/     # must be empty
  git branch -D openhelm-only                                 # clean up
  ```
- **EXPECTED:** The split succeeds and the resulting history reads as a standalone project. No
  commit in it references parent-repo paths, and no message references the decompilation work.
- **VERIFY:** The `decompiled/`/`assets/` query returns nothing; scan the messages for anything that
  would not make sense in a public repository.
- **PASS/FAIL:** PASS if the extracted history is self-contained and its messages stand alone.

---

### 12.5 Licence and notice are present and consistent

- **STEPS:**
  ```bash
  cd openhelm
  head -5 LICENSE; head -20 NOTICE
  grep -n "Licence\|License" README.md
  ```
- **EXPECTED:** Apache-2.0 licence text present, `NOTICE` present, and the README's licence section
  agrees with them.
- **PASS/FAIL:** PASS if all three agree. FAIL on a missing or contradictory licence.

---

### 12.6 Naming and compatibility claims are accurate, not implied endorsement

- **STEPS:** Read `README.md` and `CLEAN-ROOM.md`.
- **EXPECTED:** The project is called **OpenHelm**, never a vendor mark. Compatibility is stated as
  **fact** — "works with Raymarine c/e/a-Series Wi-Fi MFDs" — which is accurate, descriptive and not
  a claim of endorsement. There is a plain disclaimer of affiliation. No vendor logo or trade dress
  is used as branding anywhere, including the launcher icon.
- **VERIFY:** The README carries the disclaimer; the launcher icon is this project's own drawing.
- **PASS/FAIL:** PASS if compatibility is stated as fact and affiliation is disclaimed. FAIL if any
  vendor mark is used as branding.

---

### 12.7 Dependencies are all public and licence-compatible

- **STEPS:**
  ```bash
  cd openhelm
  grep -nE "implementation|api\(|ksp\(" app/build.gradle.kts protocol/build.gradle.kts
  git ls-files | grep -iE '\.(jar|aar|so)$' || echo "  no vendored binaries - PASS"
  ```
- **EXPECTED:** Only public artifacts (AndroidX/Compose, Hilt, coroutines, DataStore, Kotlin
  stdlib). **No bundled native libraries**, no vendor jars, nothing extracted from an APK. The
  `protocol` module has **zero** dependencies beyond `kotlin("test")` for its own tests — no Android
  types at all, which is what lets it be unit-tested in milliseconds and reused by desktop tooling.
- **VERIFY:** The dependency list is all well-known public artifacts, and the second command finds
  no tracked binaries (the Gradle wrapper jar is the sole permitted exception).
- **PASS/FAIL:** PASS if every dependency is public and no vendor binary is vendored. FAIL on any
  local `.jar`/`.so`/`.aar` of unclear origin.

---

### 12.8 The documented facts match the code

The protocol spec is the input this app was written from; the two drifting apart is how a silent
interoperability bug gets in.

- **STEPS:** Cross-read `openhelm/docs/protocol.md` against `protocol/src/main/kotlin/...`:
  - magic `45 43 52 52`, 9-byte header, little-endian payload length;
  - opcodes 1 button / 2 zoom / 3 touch, with zoom explicitly **not** modelled as a pointer;
  - the keycode table;
  - the version byte parsed as **hex from characters [2,4)** of the advertised string;
  - touch normalised 0..65535 with clamping, `seq` 0 on down/up and incrementing per move.
- **EXPECTED:** They agree, and the golden byte vectors in the tests match the worked examples in
  the doc.
- **VERIFY:** `./gradlew :protocol:test` passes, and the documented example frames appear as test
  vectors.
- **PASS/FAIL:** PASS if code, tests and spec agree. FAIL on any divergence — fix whichever is
  wrong and say which in the commit.
