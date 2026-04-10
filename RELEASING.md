# Releasing PokeClaw

This repo now assumes a single stable release signing key.

Once a public APK is shipped with that key, every later public APK must use the same key or Android will reject in-place upgrades.

## 1. Prepare the stable keystore once

Generate one long-lived release keystore and keep it outside the repo.

Recommended local inputs:

```bash
export KEYSTORE_FILE=/absolute/path/to/pokeclaw-release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=pokeclaw-release
export KEY_PASSWORD=...
```

`app/build.gradle.kts` reads these values from either:

1. environment variables, or
2. `local.properties`

Do not commit either the keystore or the secrets.

## 2. Mirror the same key into GitHub Actions

The tag-based release workflow expects these repo secrets:

- `ANDROID_KEYSTORE_B64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_B64` should be the base64-encoded keystore file:

```bash
base64 -w 0 "$KEYSTORE_FILE"
```

Without these secrets, `.github/workflows/release.yml` will fail closed and refuse to publish a public APK.

## 3. Prepare a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Add the changelog entry in `README.md`
3. Build locally first:

```bash
./gradlew :app:assembleRelease
sha256sum app/build/outputs/apk/release/*.apk
```

4. Smoke-test the signed APK on a device
5. Push `main`
6. Push the tag:

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push pokeclaw vX.Y.Z
```

The GitHub Actions workflow will then create the GitHub Release, upload the signed APK, and attach `SHA256SUMS.txt`.

## 4. Known historical limitation

The old public debug-signing path and the later public `v0.5.0` APK were signed with different keys.

That mismatch is already shipped, so Android cannot retroactively upgrade those installs in place without the original lost signing key. For that cohort, the only honest path is:

- show the in-app update prompt
- explain that Android may require a one-time uninstall + reinstall

Stable signing from `v0.5.1` onward prevents this problem from repeating.
