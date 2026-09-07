# Releasing

Cutting a release is pushing a tag:

```bash
git tag v1.2.3
git push origin v1.2.3
```

[`.github/workflows/release.yml`](../.github/workflows/release.yml) triggers on any tag matching
`v*.*.*`, builds `:app:assembleDebug` and `:app:assembleRelease` against that tag, and publishes
both APKs on a GitHub Release named after it. A tag with a hyphenated suffix (`v1.2.3-beta1`)
publishes as a prerelease; a plain `vX.Y.Z` tag publishes as a full release.

The build's `versionName` is the tag with its leading `v` stripped, and `versionCode` is the
workflow run number — both passed to Gradle as `-P` properties, so a local
`./gradlew assembleDebug` is unaffected and keeps using the checked-in defaults in
[`app/build.gradle.kts`](../app/build.gradle.kts).

If a workflow run needs to be retried against a tag that already exists — for example after fixing
a repository secret — run it manually from the Actions tab (`workflow_dispatch`) with that tag
name, rather than deleting and re-pushing the tag.

## The signing key

The release APK must be signed the same way every time — see [CLEAN-ROOM.md](../CLEAN-ROOM.md) for
why the key itself is never committed. `app/build.gradle.kts` reads it from four names, checked
first as Gradle properties (`~/.gradle/gradle.properties`, for a local release build) and then as
identically named environment variables (for CI):

| Name | Contents |
|---|---|
| `OPENHELM_STORE_FILE` | Path to the `.jks`/`.keystore` file |
| `OPENHELM_STORE_PASSWORD` | Keystore password |
| `OPENHELM_KEY_ALIAS` | Alias of the signing key within the keystore |
| `OPENHELM_KEY_PASSWORD` | Password of that key |

CI can't point at a file on disk, so the repository instead holds the keystore itself, base64-encoded,
as a secret named **`OPENHELM_STORE_BASE64`**; the workflow decodes it to a temp file for the
duration of the build and deletes it afterwards. Set these four repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `OPENHELM_STORE_BASE64` | `base64 -w0 path/to/openhelm-release.jks` |
| `OPENHELM_STORE_PASSWORD` | same as the local `OPENHELM_STORE_PASSWORD` |
| `OPENHELM_KEY_ALIAS` | same as the local `OPENHELM_KEY_ALIAS` |
| `OPENHELM_KEY_PASSWORD` | same as the local `OPENHELM_KEY_PASSWORD` |

Without `OPENHELM_STORE_BASE64` the workflow fails fast with a clear message rather than publishing
an unsigned release APK — Android has no "install anyway" for a missing signature the way it does
for an unknown source, so an unsigned artifact on the release page would simply not install.

The debug APK needs none of this: its build type signs with the auto-generated debug keystore, the
same way it does for a local `./gradlew assembleDebug`.
