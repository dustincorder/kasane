# Safe Android build, test and install workflow

## Instrumentation

Instrumentation is debug-only. Debug builds use application ID `cord.kasane.music.dev`; release remains `cord.kasane.music`. The normal target is `debug`, and `connectedAndroidTest`, `connectedDebugAndroidTest`, `connectedReleaseAndroidTest` and `connectedCheck` are blocked in Gradle.

Use the disposable Gradle-managed emulator task:

```bash
cd android
./gradlew kasaneApi35DebugAndroidTest
```

The first run may require the Android SDK API 35 AOSP emulator image. Do not replace this with a connected-device task. Do not pass production/release package artifacts as test installables.

## Release APK upgrades

Only install an already-installed package as an in-place update after verifying package ID, version monotonicity, and signing certificate:

```bash
scripts/android-safe-install.sh <adb-serial> android/app/build/outputs/apk/release/app-release.apk
```

The script refuses a fresh install, split/unresolvable base package, mismatched signer, package ID mismatch, or version downgrade. It invokes only `adb install -r`; it has no uninstall, clear-data, or retry-as-fresh-install path. If preflight fails, stop and investigate the signing/install history.

## Exporting local data

Profile → **Export local data** uses Android's document picker and writes JSON containing device Favorites and listening History. The export schema also has preferences and local-playlist fields for future persistence; in the current app those are empty because those settings/playlists are not yet stored. Account cookies, tokens, encrypted session files, and Media3 stream URLs are excluded by the export model.
