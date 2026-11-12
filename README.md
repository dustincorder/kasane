# Kasane — v0.1.1-alpha

<p align="center"><img src="assets/logo.svg" alt="Kasane" width="128" height="128"></p>

Kasane is a music player for Android and Linux. This is an early source-only alpha; interfaces and provider behavior are still evolving.

## Current status

| Area | Status |
|---|---|
| Android | Active development |
| Linux | Active development |
| YouTube Music guest streaming | One full public IOS HLS track verified in Media3; SABR-only content remains unsupported, with no automatic ANDROID_VR fallback |
| Search and catalog | Implemented on Android |
| Local favorites and listening history | Implemented |
| Google login | Android Web-session login implemented and user-confirmed |
| YouTube Music account library | Partial authenticated browse support on Android |
| Local audio playback | Planned |
| Offline downloads | Planned |
| Automix | Planned |

Current playback/radio evidence and account-verification limits are recorded in [Android core reliability](docs/android-core-reliability.md).

The Android client uses Kotlin, Jetpack Compose, Room, Media3, and ExoPlayer. The Linux client uses Rust, Tauri 2, Svelte, and SQLite. The clients do not yet share a unified library or playback queue.

## Build Android

Requirements: JDK 17 and Android SDK 35.

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Build Linux

Requirements: Rust stable, Node.js 20+, npm, and Tauri's Linux system dependencies (WebKitGTK 4.1, GTK/AppIndicator, librsvg, ALSA, OpenSSL, and patchelf).

```bash
npm ci
npm run check
cargo test --workspace
npm run build
npx tauri dev
```

Build a Linux package with `npx tauri build` after installing the platform dependencies listed in the [Tauri Linux prerequisites](https://v2.tauri.app/start/prerequisites/#linux).

## Project documentation

- [Architecture and component map](docs/agent-map.md)
- [Android architecture](docs/architecture/android.md)
- [Linux architecture](docs/architecture/linux.md)
- [Development and testing](docs/development.md)
- [Agent guidelines](AGENTS.md)

The project license has not been selected. The repository is publicly viewable, but no project license has been granted. No release binaries are published.
