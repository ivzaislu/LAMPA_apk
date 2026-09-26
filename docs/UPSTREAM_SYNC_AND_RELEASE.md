# Upstream sync and custom release channel

This fork keeps the LAMPA application version aligned with upstream while publishing and installing updates only from this repository.

## Version source

The APK version is derived from `origin/main`:

- `versionCode` = commit count of `origin/main`
- `versionName` = latest upstream tag reachable from `origin/main`

CI fetches tags from `lampa-app/LAMPA` before every build, so the custom branch commits do not change the application version. For example, when upstream `main` is release `v1.13.1`, the custom APK is also `1.13.1`.

## Update source

The Lite APK has updates enabled, but `Updater.kt` reads releases from:

`ivzaislu/LAMPA_apk`

It does not read releases from `lampa-app/LAMPA`.

Only published, non-prerelease releases are considered. The updater selects an APK asset explicitly instead of taking an arbitrary last release asset.

## Release naming

Custom GitHub releases use the upstream version plus a suffix:

`v<upstream-version>-custom`

Example:

`v1.13.1-custom`

The APK inside still reports the upstream version (`1.13.1`). This avoids colliding with upstream Git tags while keeping the installed app version aligned with upstream.

## Automated release pipeline

`.github/workflows/custom-apk.yml`:

1. fetches upstream `main` tags;
2. verifies the Android TV channel master-switch guards;
3. verifies the custom update-channel invariants;
4. builds debug/instrumentation artifacts;
5. runs the Android TV OFF/ON tests on a real Android TV emulator;
6. restores the release keystore from GitHub Secrets;
7. builds `Lite Release`;
8. verifies that APK `versionName` and `versionCode` match `origin/main`;
9. verifies the APK signature and `RELEASE_CERT_SHA256`;
10. creates a GitHub Release in `ivzaislu/LAMPA_apk` if that upstream version has not already been published.

A release is therefore not published unless both the signed release build and Android TV runtime tests pass.

## Automated upstream sync

`.github/workflows/sync-upstream.yml` runs daily and can also be started manually.

It:

1. fetches `lampa-app/LAMPA:main`;
2. fast-forwards this fork's `main`;
3. merges upstream `main` into `optimize-tv-channel-updates`;
4. runs the static custom invariants;
5. pushes the merge only if there are no conflicts and the invariants still pass.

If upstream changes conflict with the customization, the workflow fails instead of force-pushing or silently dropping custom behavior.

The next push to the custom branch triggers the full build/runtime-test/release pipeline.

## Signing

The official custom release requires these GitHub Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `RELEASE_SIGN_KEY_ALIAS`
- `RELEASE_SIGN_KEY_PASSWORD`
- `RELEASE_CERT_SHA256`

The release job fails if the APK certificate differs from `RELEASE_CERT_SHA256`.

The currently configured certificate is not the certificate used by the previously uploaded old TV-box APK, so that old APK cannot be updated in place without its original signing key.
