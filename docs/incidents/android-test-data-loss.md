# Android connected test data-loss incident

Date: 2026-11-12  
Affected app: `cord.kasane.music` on the user's Pixel 6 Pro (`raven`)  
Outcome: UTP removed the app under test after test-APK installation failed. The signed release APK was subsequently reinstalled, but the app's private data directory was already absent.

## Evidence and exact sequence

Local Gradle UTP output is in `android/app/build/outputs/androidTest-results/connected/release/Pixel 6 Pro - 16/utp.0.log`. It records:

1. UTP configured `app-release.apk` (`cord.kasane.music`) and `app-release-androidTest.apk` (`cord.kasane.music.test`) as installables.
2. Both installable entries had `uninstall_after_test: true`.
3. UTP installed the release APK first.
4. Installation of the release instrumentation APK then failed. `test-result.textproto` records `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package cord.kasane.music.test signatures do not match newer version`.
5. Despite failure during the installer's `beforeAll`, UTP ran cleanup and logged `Uninstalling cord.kasane.music` and `Uninstalling cord.kasane.music.test`.

The app disappeared because the UTP AndroidTest APK installer configured the target release APK for post-test uninstall and ran cleanup after setup failure. This is corroborated by UTP's own log and Android's installer-plugin source, whose setup installs configured APKs and whose cleanup uninstalls entries marked `uninstallAfterTest` ([installer implementation](https://android.googlesource.com/platform/tools/base/+/e0acba79c57db4d41af75b253c0243ad9c01138f/utp/android-test-plugin-host-apk-installer/src/main/java/com/android/tools/utp/plugins/host/apkinstaller/AndroidTestApkInstallerPlugin.kt)).

The release test target was created by temporarily setting `testBuildType = "release"` to run a network-only instrumentation probe against the existing release install. The test APK retained package name `cord.kasane.music.test`; that package already existed from an earlier test build signed by another certificate. The incoming release test APK therefore could not update it. Android returned `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. A local `apksigner` comparison of the current debug and release androidTest artifacts confirms their certificates differ; the exact certificate digest of the historical installed test APK was not captured. The mismatch was not checked before starting the connected task.

After the connected task, read-only Package Manager checks showed no installed `cord.kasane.music`; before reinstall, `/data/user/0/cord.kasane.music` and `/data/user_de/0/cord.kasane.music` were absent. The app manifest sets `allowBackup="false"`. Reinstalling the APK did not and cannot restore the deleted app data. The encrypted session and local Room data are therefore presumed lost. No further account operation was performed.

## Contributing factors

- `testBuildType = "release"` made `connectedReleaseAndroidTest` available for a user-owned release application ID.
- The target package was not isolated from instrumentation tests.
- No preflight compared the installed and incoming test-APK certificates.
- The Android Gradle/UTP flow was treated as a non-destructive update, despite its explicit `uninstall_after_test` cleanup behavior.
- The user Pixel was used as the connected-test target instead of a disposable emulator or dedicated test device.

## Prevention implemented

- Debug builds use application ID `cord.kasane.music.dev`; release stays `cord.kasane.music`.
- `testBuildType` is explicitly `debug`, so release instrumentation is not generated as the normal target.
- Gradle rejects every connected instrumentation task. The supported instrumentation target is a disposable Gradle-managed API 35 emulator; debug application ID is `.dev` and `testBuildType` is explicitly debug.
- `scripts/android-safe-install.sh` is the supported release-upgrade path. It requires an installed package, checks application ID, version monotonicity and signer certificate equality before issuing only `adb install -r`. It has no uninstall/clear fallback and verifies the package remains installed afterward.
- Local JSON export is available for local favorites and history. It has no field for cookies/tokens/Keystore material. The current app has no persisted preferences or local playlist feature, so those sections export empty collections until those models exist.

These safeguards avoid connected instrumentation entirely. Pixel installation/testing is not used for Gradle tests.

## Not established

- The exact certificate SHA-256 values of the preexisting debug test APK and incoming release test APK were not retained.
- The original private data cannot be recovered from the reinstalled APK. No claim of recovery is made.
- No test was run again on the Pixel after the incident.
