# Kasane as one Kotlin Multiplatform application (Android + Linux)

Branch `exp/compose-desktop`. Built from `android/` (`./gradlew :app:assembleRelease`, `:desktop:run`,
`:desktop:createDistributable`). The Tauri client is untouched and still builds; it is no longer the target.

## Modules

| Module | Targets | Holds |
|---|---|---|
| `:shared` (`shared/`) | android, jvm | `commonMain`: models (Track, Catalog), InnerTube client + parsers, `QueuePolicy`, import/export, mix analysis, Connect device/prompt models. `jvmCommonMain` (android + jvm): OkHttp transport, guest/iOS stream resolvers, `AudioRange`, the whole Kasane Connect core (`ConnectManager`, TLS, protocol, SAS, trust). |
| `:ui` (`shared-ui/`) | android, jvm | Compose Multiplatform UI used by both apps (below). |
| `:app` (`android/app`) | Android | Activity, Media3 playback/MediaSession, Room, WebView sign-in and verification, NSD, Material You colour selection, remaining Android screens. |
| `:desktop` (`desktop-compose/`) | JVM | Window, mpv engine, display-scale detection, mDNS responder/browser, desktop Connect adapter, remaining desktop screens. |

### UI code in common source sets (`shared-ui/src/commonMain`)

- `theme/Theme.kt`: shape and type scales, accent presets, `seedColorScheme` (HCT/Material You algorithm), `KasaneMaterialTheme`, muted text styles. Android passes its wallpaper `dynamic*ColorScheme` (Android 12+) into it, so Material You is preserved; desktop passes a seed scheme.
- `settings/Appearance.kt`: `ThemeMode`, `AccentSource` (Android's `AppSettings` aliases them).
- `components/Artwork.kt`: `TrackArtwork` with size-bucketed artwork; platforms provide an `ArtworkLoader` (Coil on Android, OkHttp + Skia on desktop).
- `components/TrackListItem.kt`: track row with playing/loading state, overflow menu, long-press, accessibility actions.
- `components/ConnectComponents.kt`: device icons and state labels, `DeviceRow`, the pairing dialog.
- `screens/QueueScreen.kt`: the complete queue screen (now playing, up next in real order, played, drag reorder, swipe/menu remove). Android hosts it in a bottom sheet; desktop in the side panel and Now Playing.
- `screens/AppearanceSection.kt`: Appearance settings (theme, colour source, accents, custom hue) with a platform slot (desktop interface scale).
- `screens/ConnectDevicesScreen.kt`: Settings → Kasane Connect (switch, paired devices with real state, nearby devices, pair by address, forget).

Next to move, in order: catalog page (album/artist/playlist), search results, home shelves, player controls and the Now Playing layout (with per-form-factor wrappers), library. Navigation stays per platform until the screens are shared.

## Desktop scaling

The JVM reports scale 1.0 under KDE Wayland even at 165 %, which is why the UI was tiny. `SystemScale` reads the real scale (KASANE_SCALE, GDK/Qt variables, `kscreen-doctor -o`, Xft.dpi) and `ScaledDensity` multiplies the window density by it and by the user's interface scale (Settings slider 75–250 %, Ctrl +/−/0, persisted), never twice when the JVM already scaled. Every dp/sp scales through Compose density; `ScaleTest` measures rendered pixels (100 dp → 165 px at 1.65).

## Kasane Connect: root causes fixed here

- **Self-discovery.** Two Kasane instances on one computer have different identities, so id filtering alone listed the other one. Discovery now drops this device's id *and* any address bound to this machine; a client that reaches itself (same id in `hello`) closes the session.
- **Duplicate devices / desktop icon on a phone.** Entries were keyed `id ?: host:port`: a half-resolved mDNS record (TXT not yet arrived) or a manual address created a second, platform-less entry, drawn with the computer icon. Records without an id are ignored, address entries are folded into the identified device, unknown platforms get a neutral icon, and lost services expire (Android lost events carry only the service name; JmDNS removals may lack TXT, both now mapped back).
- **Desktop invisible to the phone.** The desktop announced on its ZeroTier interface (first site-local address) and Android's mDNS rejected JmDNS's packets. A minimal responder now advertises on the default-route interface; JmDNS only browses and re-queries so a phone's new Wi-Fi address is picked up.
- **Pairing.** The initiator shows the code and waits; the receiver's user confirms once; trust is stored on both sides. The SAS commit/reveal, mutual TLS and certificate pinning are unchanged. Trade-off: the initiating device trusts the result reported by the receiver, so code comparison by the receiving user is the authentication; an active attacker during pairing could make only the initiator believe pairing succeeded after the receiver's user rejected it. Mutual confirmation would remove that, at the cost of the second tap.
- **States.** Available, pairing, paired, connected, playing, paused/idle and offline are derived from discovery, trust, the session and remote playback, not conflated.

Also fixed: handoff to the phone crashed (MediaController used off its thread); remote volume changes are announced; the stale-trust dead end ("X trusts this device but...") resets and re-pairs.

Android limitation found on the test phone: with Android's *Restricted networking mode* on, a backgrounded app has no network, so Connect to the phone works only while Kasane is in the foreground or playing (foreground service). Not something the app can override.

## Offline library: implementation path

What exists: Android has the offline core (Room `audio_files` with QUEUED/DOWNLOADING/SAVED states, separate saved store and LRU stream cache with configurable limit, `OfflineGateInterceptor` that blocks network in Offline Only mode, saved-music screen). Desktop has only a JSON liked/history file.

1. **Shared schema.** Move `TrackRecord`, `AudioFileRecord`, history, favorites, playlists and the catalog cache to `:shared` with Room KMP (Android + JVM, bundled SQLite driver), keeping the current Android table names and a migration so existing data stays. Desktop gets the same database under `~/.local/share/cord.kasane.compose/`.
2. **Library model.** One `LibraryItem` source enum: `LOCAL_FILE` (path/URI, imported folder id), `SAVED_ONLINE` (permanent, never evicted), `CACHED_STREAM` (evictable), plus user playlists, favorites, history. Eviction runs only over `CACHED_STREAM`.
3. **Modes.** `NetworkMode { ONLINE, OFFLINE }` in shared settings; the OkHttp gate moves to `jvmCommonMain` so both apps block YouTube requests in Offline mode; shared screens render online-only items as unavailable instead of hiding them.
4. **Import.** Shared `LocalImportPlanner` (dedupe by content hash + path, unavailable-path state, copy/move only on request). Platform scanners: Android Storage Access Framework (`OpenDocumentTree`, persisted permissions, `MediaMetadataRetriever` for tags/artwork); desktop file/folder picker (xdg-desktop-portal FileChooser, AWT fallback) and a JVM tag reader for MP3/FLAC/Ogg/Opus/M4A/ALAC/WAV. Playback: Media3 on Android, mpv on desktop (both decode those formats).
5. **Later imports.** The existing `ImportPlanner`/`TrackMatcher` (source ids, candidates, confirmation) take archive (ZIP/RAR/7z with path-traversal and size limits), InnerTune/OpenTune/Metrolist backups (InnerTune adapter exists) and service playlists matched by metadata; a Spotify playlist never implies audio access.

## Google sign-in on desktop

Shareable now: the session model (`AccountSession`), signed InnerTube requests (`SAPISIDHASH`), account library/playlist/like parsing (all in `:shared`). Platform adapters needed:
- **Login capture.** Compose Desktop has no WebView. Options: embed Chromium (JCEF/KCEF, ~100 MB) and reuse Android's cookie-capture rules; or read the session the Tauri client already stores on this computer (Secret Service, `cord.kasane.desktop` / `youtube-session`), which is this machine's own credential, never Android's.
- **Secure storage.** Secret Service/KWallet via D-Bus, memory-only fallback (Tauri already does this).
- **Verified playback.** Full-length signed-in streams on Android come from WEB_REMIX with a BotGuard proof run in a WebView (`VerifiedPlayerResolver`); the desktop needs the same JS runtime (JCEF) for it. Until then the desktop plays full tracks through the iOS HLS profile when YouTube offers it, and stops with an explanation when YouTube cuts a guest stream after its first part (it now does that for ANDROID_VR guest URLs).

The Android account session is not touched by any of this.

## Feature parity (Android vs desktop), current state

| Area | Android | Desktop | Shared code today |
|---|---|---|---|
| Theme, accents, light/dark | yes, Material You | yes, seed colours, KDE/GNOME dark | theme, Appearance screen |
| Interface scale / DPI | system | detected + user scale | — |
| Home, mood chips | yes, cached | yes, guest | catalog client |
| Search | yes, suggestions, history | yes | catalog client |
| Album / artist / playlist pages | rich | basic | catalog models |
| Queue screen | yes | yes | **queue screen** |
| Player bar / Now Playing | sheet with gestures | bar + full window | — (next) |
| Radio, autoplay | yes | yes | QueuePolicy |
| Liked songs, history | Room | JSON | — (Room KMP planned) |
| Account sign-in, cloud likes, account playlists | yes | no | session + requests |
| Verified / age-restricted playback | yes | no | — |
| Guest full-length playback | via account/verified paths | iOS HLS when offered | IosHlsResolver |
| Offline saving, stream cache, Offline Only | yes | no | — (planned) |
| Local file import | partial (backup import) | no | ImportPlanner |
| Connect pairing/control/handoff | yes | yes | **core + settings screen + dialog** |
| Media keys / notifications | MediaSession | no MPRIS yet | — |
| Lyrics, AutoMix, SponsorBlock, silence skip | no / partial | no | mix analysis |
