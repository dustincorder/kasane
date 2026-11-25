# Kasane — v0.2.0-alpha

<p align="center"><img src="assets/logo.svg" alt="Kasane" width="128" height="128"></p>

Kasane is a YouTube Music client for Android and Linux. Both clients are Kotlin Multiplatform
(Jetpack Compose / Compose Desktop) and share catalog, playback, library, offline, lyrics and
Connect logic. This is an early alpha: interfaces and provider behavior are still evolving.

## Clients

| Client | Stack | Status |
|---|---|---|
| Android (`cord.kasane.music`) | Kotlin, Jetpack Compose, Media3/ExoPlayer, Room | Active development |
| Linux (`cord.kasane.desktop`) | Compose Desktop (KMP), bundled JVM runtime, mpv audio engine | Active development, target Linux client |
| Tauri/Rust desktop (`crates/`, `src-tauri/`, `src/`) | Rust + Tauri 2 + Svelte 5 | **Frozen** — kept in-tree, not developed, not deleted |

## Features

- YouTube Music catalog: Home shelves, five-type search, artist/album/playlist pages.
- Playback: verified Web stream resolution with a BotGuard proof-of-origin, iOS HLS fallback,
  queue with play-next/reorder/remove, shuffle, repeat, and radio continuation.
- Account: Google sign-in (Android WebView, Linux system WebKitGTK) or guest mode; account
  library, likes and remote listening history when signed in.
- Offline: saved music with resumable downloads, listen cache, Offline Only mode — on both
  Android and Linux.
- Lyrics: synchronized (word/line) lyrics from AMLL TTML DB, LRCLIB and YouTube Music, manual
  source selection and ±250 ms offset.
- Release metadata: MusicBrainz + Cover Art Archive enrichment for saved tracks.
- Kasane Connect: TLS pairing between devices, remote control (queue, seek, volume,
  shuffle/repeat) and playback transfer with position handoff.

## Install Linux

The alpha ships as a self-contained tarball (bundled JVM runtime; `mpv` is the only external
runtime dependency):

```bash
tar -xzf kasane-desktop-linux-x86_64-0.2.0-alpha.tar.gz
cd kasane-desktop-0.2.0-alpha
./install.sh        # installs to ~/.local/opt/kasane-compose + desktop entry, no sudo
```

`install.sh` is idempotent and never touches user data under `~/.local/share/cord.kasane.compose/`.
To run without installing: `./kasane` from the unpacked directory.

Requirements: `mpv` 0.38+ in `PATH` (audio engine, spawned via IPC — not linked), WebKitGTK
(for the sign-in helper and stream verification), a working X11/Wayland desktop.

## Build from source

### Android

Requirements: JDK 17 and Android SDK 35.

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

### Linux (Compose Desktop)

Requirements: JDK 21 toolchain, `mpv`, Node 20+ (to build the embedded verifier bundle).

```bash
npm run build:verifier            # verifier/dist/kasane-verifier.js is required by the build
cd android
./gradlew :desktop:run            # run from source
./gradlew :desktop:test           # unit tests
./gradlew :desktop:createDistributable   # AppDir under desktop-compose/build/compose/binaries/
```

Headless verification modes: `:desktop:run --args="--smoke"` (playback), `--connect-smoke`
(two-instance Connect loopback), `--render DIR` (screenshots).

### Legacy Rust/Tauri client (frozen)

`crates/`, `src-tauri/` and the Svelte `src/` are the pre-KMP desktop client. Per project
decision D1 (`docs/ROADMAP.md`) the code is kept for reference and Connect protocol parity but
receives no feature work. It still builds (`cargo test --workspace`, `npm run check`,
`npx tauri build --no-bundle`) but is not a supported install path.

## Known limitations

- **Alpha quality**: expect rough edges; interfaces and behavior change between releases.
- **Guest playback is best-effort**: YouTube may interrupt anonymous streams (~30 s cap) or
  require sign-in for some tracks; signed-in playback via the verified resolver is the
  reliable path.
- **SABR-only tracks** are not playable yet; a small share of catalog entries may fail.
- **Connect** is verified in loopback smoke tests; multi-device behavior on real networks is
  still being validated.
- **Linux**: requires `mpv` and WebKitGTK; Wayland/X11 windowing quirks possible; distribution
  is ~180 MB because of the bundled JVM.
- **No license selected yet** — see below.

Internal, engineering-level issue tracking lives in `docs/KNOWN_ISSUES.md` (Russian).

## Project documentation

- [Architecture](docs/ARCHITECTURE.md) and [feature matrix](docs/FEATURE_MATRIX.md) (which
  client implements what, with evidence)
- [Product spec](docs/PRODUCT_SPEC.md), [roadmap](docs/ROADMAP.md)
- [Development and testing](docs/DEVELOPMENT.md)
- [Connect protocol](docs/connect/protocol.md)

Most internal documentation is in Russian; the README and public docs are in English.

## License

The project license has not been selected. The source is publicly viewable, but no license is
granted yet — all rights reserved by the author until one is chosen.
