# Clean-room provenance

OpenHelm is intended to be split out of its parent repository and published as open source. That
parent repository also contains a decompilation of Raymarine's 2017 `RayRemote` APK, done for
interoperability research. **None of that material may end up here.**

This document is the rule, the reasoning, and the check.

---

## The rule

**Nothing in `openhelm/` may be derived from Raymarine's copyrighted expression.**

Concretely, the following must never appear in this directory or its git history:

| ❌ Never | Why |
|---|---|
| Decompiled Java, smali, or `.dex` from any Raymarine APK | Copyrighted expression, even after decompilation. A decompilation is a derivative work. |
| Raymarine artwork — button PNGs, icons, backgrounds, `control_*.png` | Copyrighted expression. |
| Raymarine layout XML, `public.xml`, resource IDs, or string resources | Copyrighted expression. |
| Class, method, or field names copied from the decompile (`ad`, `an`, `ao`, `RayImageButton`, …) | Traceable to the original; nothing is gained by reusing them. |
| The names *RayRemote*, *RayControl*, *Raymarine*, *Axiom*, or Raymarine logos as **branding** | Trademarks. See "Naming" below. |

## What *is* used, and why that is a different thing

OpenHelm is built from a **protocol description** — a document of facts about bytes on a wire,
written by this project:

- frame layout, opcode numbers, payload field order and widths
- the keycode table
- the mDNS service types and TXT key names
- the coordinate normalisation formula
- observed device behaviour (opcode 2 zooms; auto-repeat is MFD-side; TCP interleave hangs)

**Facts and interface specifications are not copyrightable expression.** Interoperating with a
device you own — and reverse-engineering a protocol in order to do so — is a well-established
practice, and in the United States has been repeatedly upheld where the purpose is compatibility
rather than copying (*Sega v. Accolade*; *Sony v. Connectix*). What is protected is Raymarine's
*code and artwork*, and none of it is here.

> ⚠️ **This is engineering practice, not legal advice**, and none of the authors is a lawyer. If
> OpenHelm is ever published under an organisation's name, get the position reviewed by someone
> qualified — particularly on trademark use and on the DMCA §1201 interoperability exception.

## Naming

The project is called **OpenHelm**, not any Raymarine mark. Compatibility may be stated as **fact**
— "works with Raymarine c/e/a-Series Wi-Fi MFDs" — because that is accurate, descriptive, and not a
claim of endorsement. It must never be branded, logo'd, or named to imply Raymarine published or
approved it.

## Artwork

Every icon and control graphic in this project is **drawn from scratch**, as Compose vector code or
original SVG. The original app's button artwork is not traced, recoloured, or used as a reference
image. Where the original layout is echoed, it is because **the MFD's physical keypad** has those
functions (Home, Menu, Back, Range, WPT) — that is a hardware fact, not a copied design.

## The check

Before publishing, and in CI once this is split out:

```bash
# 1. No file may reference the original package or its obfuscated class names
grep -rniE 'com\.raymarine|RayRemote|RayControl|smali|apktool' --include='*.kt' --include='*.kts' --include='*.xml' .

# 2. No binary assets should have arrived from anywhere but this project
find . -type f \( -name '*.png' -o -name '*.jpg' -o -name '*.webp' \) -not -path './.git/*'

# 3. The subtree's history must not contain parent-repo paths
git log --oneline -- decompiled/ assets/
```

Expected: (1) matches only in documentation *discussing* provenance, such as this file; (2) empty
or only files authored here; (3) empty.

**One expected exception.** The strings `raymarine-mfd-rtsp-path`, `raymarine-mfd-model`,
`raymarine-mfd-serial` and `raymarine-mfd-rrc-version` appear as constants in `protocol/`. These are
**DNS-SD TXT keys — literal bytes on the wire**. A client that does not send exactly those strings
cannot talk to the device at all, so they are protocol facts of the same kind as an opcode number,
not branding and not copied expression. The check above is written not to flag them; that is
deliberate, not an oversight.

## Splitting out

This directory is developed inside the parent repo but committed so it can be extracted cleanly:

```bash
git subtree split --prefix=openhelm -b openhelm-only
```

**Commits touching `openhelm/` should touch nothing else**, so the extracted history reads as a
standalone project and no commit message references the decompilation work.
