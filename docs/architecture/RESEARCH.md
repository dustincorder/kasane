# Reference Research: LiMusic & BitChord

## 1. LiMusic (SimoHypers/limusic)

### Architecture & Tech Stack:
- **Core**: Rust, Tauri, Svelte, TypeScript.
- **Playback Engine**: Uses `libmpv` binding for Linux/macOS/Windows. This offloads codec decoding, gapless playback, and audio normalization directly to mpv's battle-tested C codebase.
- **YouTube Music Provider**: Directly queries InnerTube endpoints (such as `music.youtube.com/youtubei/v1/search` and `player`).
- **Stream Extraction**: Retrieves streaming audio formats (adaptive audio Opus/AAC webm/m4a) from InnerTube player responses, extracting direct audio URLs or resolving cipher/throttling signatures when present.
- **Local Library**: Scans local paths and parses ID3/Vorbis tags.

### Key Insights for VibePlayer:
1. **InnerTube Client Setup**: InnerTube requires specific client headers (`User-Agent`, `X-YouTube-Client-Name`, `X-YouTube-Client-Version`). The `WEB_REMIX` client context is ideal for YouTube Music search and metadata retrieval, while `ANDROID_MUSIC` or `IOS` clients often provide simpler stream extraction without aggressive JS cipher throttling.
2. **Audio Backend Selection**: While `libmpv` is powerful on desktop Linux, it poses cross-compilation and bundling challenges on Android. For VibePlayer, our layered architecture separates the playback interface (`PlaybackBackend` trait) so desktop can use pure Rust (`rodio`/`cpal` with symphonia or gstreamer/mpv) while Android delegates directly to Android Media3/ExoPlayer.
3. **No DRM Circumvention**: VibePlayer only accesses standard publicly accessible InnerTube streaming formats; no DRM or content protection bypass is implemented.

---

## 2. BitChord (kushagrasinghx/BitChord)

### Architecture & Tech Stack:
- **Core**: Android-focused Kotlin + Jetpack Compose / cross-platform desktop UI.
- **Audio Engine**: AAudio / Android Media3 ExoPlayer with bit-perfect DAC pass-through options.
- **Automix & Transitions**: Custom transition cues and crossfading routines based on beat timing.
- **Design Philosophy**: Polished, album-art-centric typography inspired by modern high-end players.

### Key Insights for VibePlayer:
1. **Automix Layering**: Automix cannot be a monolithic crossfade function. It requires:
   - **Analysis Stage**: Pre-extracting BPM, key, and phrase boundaries (stored in local SQLite).
   - **Planning Stage**: Determining transition window (e.g. 4 to 8 beats before outro/intro boundary).
   - **Mix Stage**: Precise gain curves and tempo adjustment (or beat-aligned cut) without distorting pitch.
2. **Media Session**: On Android, Android Media3 handles background playback, lock-screen notifications, and Bluetooth headset events. On Linux, MPRIS (via `zbus`) provides native desktop media key and lockscreen integration.

---

## 3. VibePlayer Decisions

1. **Modular Workspace**: Split into `crates/vibe-core`, `crates/vibe-audio`, `crates/vibe-providers`, `crates/vibe-db`, and `src-tauri` for clean separation.
2. **Pure Rust Audio by Default**: Desktop playback uses `rodio` / `symphonia` for zero heavy external C dependencies during initial bootstrap, keeping builds fast, portable, and memory-safe.
3. **Multi-client InnerTube Provider**: Implement InnerTube calls with a clean typed client structure, handling search, song metadata, and direct audio stream URLs.
