# Compose Multiplatform desktop prototype (experimental)

Branch `exp/compose-desktop`. Gradle project `:desktop` in `desktop-compose/`, built from `android/`.
The Tauri client (`src/`, `src-tauri/`) is unchanged and remains the shipped Linux client.

## Build and run

```sh
cd android
./gradlew :desktop:run                      # window (needs mpv installed)
./gradlew :desktop:createDistributable      # desktop-compose/build/compose/binaries/main/app/kasane-compose/bin/kasane-compose
./gradlew :desktop:test
# Headless checks, no window and no sound (mpv --ao=null):
./gradlew :desktop:run --args="--smoke [query]"   # Home, search, play, seek, pause, next
./gradlew :desktop:run --args="--connect-smoke"   # two instances: pair, state, seek, transfer, unpaired rejection
                                                  # KASANE_RUST_CLI=target/release/kasane-connect-cli adds Rust interop
./gradlew :desktop:run --args="--render DIR"      # offscreen PNGs of each screen
```

Runtime dependency: `mpv` (0.38+, for `loadfile … <index> <options>`). Data lives in
`~/.local/share/cord.kasane.compose/`, separate from the Tauri client.

## Architecture

| Layer | Shared with Android | Desktop-only |
|---|---|---|
| Catalog (Home, search, albums, artists, playlists, radio) | `shared/commonMain` InnerTube client + parsers, `OkHttpCatalogTransport` | — |
| Queue rules (new context, radio append, play next, move) | `QueuePolicy` | `DesktopPlayer` applies them |
| Stream resolution (guest ANDROID_VR) | `GuestPlayerResolver`, `StreamResponse` (moved to `shared/jvmMain`) | — |
| Bounded CDN reads | `AudioRange`, `CdnRecoveryBudget` (moved to `shared/jvmMain`) | `StreamProxy`: loopback HTTP for mpv |
| Decoding/output | — (Media3 on Android) | `MpvEngine`: private mpv process over JSON IPC |
| Kasane Connect protocol, TLS, pairing, trust | `ConnectManager`, `ConnectTls`, `Link`, `Protocol`, `Sas`, `TrustStore`, `Identity` (moved to `shared/jvmMain`) | identity (`SelfSigned` P-256 in PKCS12), JmDNS discovery, `DesktopPlayerAdapter`, `DesktopRemote` |
| UI | — | `desktop-compose/.../ui`: rail navigation, player bar, queue panel, Now Playing, Connect popover |

Why mpv: YouTube serves Opus/WebM, AAC/MP4 and HLS; mpv decodes all of them, is already a desktop
dependency on most distributions, and keeps the JVM free of native codec bindings. YouTube's direct
URLs only answer bounded `&range=` reads (the rule `YouTubeDataSource` follows on Android), so mpv
reads from a 127.0.0.1 proxy with an unguessable per-stream token that turns its Range requests into
those chunks and refreshes the signed URL once on 403/410. Signed URLs never leave the process.

The Rust backend was not reused: its catalog/stream code duplicates what `shared` already has in
Kotlin, and a JNI bridge would cost more than the ~300 lines of mpv + proxy.

## Bugs found while sharing the Connect core (fixed here, affect Android and Tauri)

- Incremental state updates were published only to outbound sessions, so a controller received a
  device's state once and then nothing (`ConnectManager.publishOnce`). Regression covered in
  `ConnectLoopbackTest.managerPairingAndCommand`.
- Controllers replaced the last frame with incremental frames that omit an unchanged queue, losing
  the remote queue; both the Kotlin client and the Rust client (`service.rs`) now keep it and send
  `sync` when the revision moved on without a queue, as `protocol.md` specifies.
- Android's controller sent `moveQueueItem`/`removeQueueItem`; the protocol and every player use
  `moveQueue`/`removeQueueIndex`, so remote queue edits from the phone always failed.

## Feature comparison: Android ↔ Compose desktop

| Feature | Android | Compose desktop | Path to parity |
|---|---|---|---|
| Home shelves, mood chips | yes | yes (guest) | shared |
| Search (songs/albums/artists/playlists/videos) | yes | yes | shared |
| Album / playlist / artist pages | yes, rich headers, artist banner | basic header, tracks, carousels, Radio/Mix | port components into a shared UI module |
| Playback, seek, next/previous, shuffle, repeat | Media3 | mpv | platform engine stays separate |
| Radio autoplay and continuation | yes | yes | shared `QueuePolicy` |
| Queue view / reorder / remove | drag-and-drop sheet | panel with arrows, remove | drag reorder in shared UI |
| Liked songs, history | Room | JSON file | move persistence behind a shared repository (SQLDelight or Room KMP) |
| Account sign-in, cloud likes, account playlists | yes | **no** | needs a desktop login WebView and a Secret Service store; the catalog side is already shared |
| Age-restricted / verified (WEB_REMIX) playback | yes | **no** (guest ANDROID_VR only) | follows sign-in; `VerifiedPlayerResolver` needs a desktop JS runtime |
| iOS HLS fallback | yes | **no** | move `HlsPlayerResolver` to shared (remove `BuildConfig` dependency) |
| Offline downloads | yes | no | shared offline policy + desktop storage |
| Lyrics, AutoMix/crossfade | partial | no | `mix/` is shared; needs an engine with two outputs |
| Material You dynamic colour | wallpaper colours | seed accent + light/dark from KDE/GNOME | same `material-kolor` tonal palettes |
| Kasane Connect: pairing, control, transfer, current-device rule | yes | yes (same core) | shared |
| Media keys / MPRIS, tray, notifications | system media session | **no** | MPRIS over D-Bus |
| Packaging | APK | app directory with bundled JRE (180 MB) | AppImage/Flatpak via `nativeDistributions` |

## What sharing UI would take

The Android UI is Compose already, but `shared` is a JVM-only KMP module that Android consumes as a
jar, so it cannot hold Compose UI for Android. Sharing screens needs a `shared-ui` KMP module with an
`androidTarget` plus `jvm`, Compose Multiplatform 1.7.x (same compose 1.7 runtime as the Android
BOM), Coil 3 instead of Coil 2, and platform seams for: system colours, insets/edge-to-edge, haptics,
WebView login, and the playback controller (`PlaybackManager` vs `DesktopPlayer` behind one interface).
Candidates to move first, in order: theme tokens (duplicated now in `ui/Theme.kt`), track rows and
cards, catalog pages, queue list, Connect sheet. Navigation and the Now Playing sheet gestures stay
per form factor.

## Recommendation

Replacing Tauri is worthwhile **only if** the UI is actually shared. This prototype already shares
the catalog, queue rules, stream resolution and the whole Connect core, and reached a working
Home → search → play → queue → Connect flow in about 2.7k lines of desktop code; the Tauri client
needs its own Rust catalog, its own Connect implementation and a Svelte UI for every feature.
Costs: a 180 MB runtime, JVM start-up time (not measured yet), an mpv dependency, and no sign-in yet. Suggested next
step before deciding: create `shared-ui`, move the theme and one catalog page into it, and confirm
the Android build and look stay identical. If that holds, port sign-in and MPRIS, then retire Tauri.
