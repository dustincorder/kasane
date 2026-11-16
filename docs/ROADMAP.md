# Kasane product roadmap — Android + Linux Kotlin Multiplatform

Source of scope: the current full-client goal (2026-11-16). This roadmap replaces
the historical Rust/Tauri plan archived in `architecture/legacy-roadmap.md`.
Status labels are defined in `ACCEPTANCE.md`; a checked build is not product completion.

## Baseline and invariants

- Android versionName 0.1.1-alpha, versionCode 2; Tauri/Cargo 0.1.1-alpha (old package.json still 0.1.0-alpha).
- Android Media3, Keystore auth, Room v3, guest catalog, cached metadata, radio and
  shared menus exist. Real account writes remain unverified. Current SABR restrictions
  and exact emulator evidence are in `sabr-playback.md`, not considered resolved.
- No intermediate Pixel APK installs, connected instrumentation, clear/uninstall,
  account reset, destructive migrations or credential extraction. Preserve safe installer.
- Current Rust/Tauri/Svelte is retained until a functioning Kotlin desktop replacement
  passes parity gates. No new Rust functionality. No branches, Actions, Releases,
  tags, APK publication, force push or new project license.
- Do not require incremental human QA. Account consent and final physical audio checks
  are deferred into one final manual checklist; use fixtures where credentials are absent.

## Internal stages and completion gates

| Stage | Deliverables and gate | Status |
|---|---|---|
| M0 Architecture | Current-state inventory, roadmap, acceptance matrix, safe build/test boundaries | IMPLEMENTED & VERIFIED (documentation/inventory) |
| M1 Shared domain | Extract existing portable Track/catalog/queue rules; Android consumes shared code; unchanged Room schema; shared and Android regression tests pass | IMPLEMENTED & VERIFIED for this extraction; desktop app/UI still absent |
| M2 Shared provider/cache/auth logic | One InnerTube implementation, typed HTTP boundary, portable parsers, account-scoped SWR cache, session lifecycle/contracts, cloud ratings and playlist operations; read/write transport tests | PARTIAL: shared request client, JSON parsers and typed catalog now used by Android; cache/session/rating controllers still platform-owned; Linux consumer absent |
| M3 Kotlin Linux vertical slice | Compose Desktop window using shared catalog, real libmpv/JNA backend, one queue, local-file playback, lifecycle cleanup, launch/audio/seek/next tests | Not started |
| M4 Shared Compose and navigation | Real Home/Search/detail/Library/Profile/Now Playing, platform-adapted navigation, actions, insets/Back, adaptive desktop and touch layouts, accessibility and UI tests | Not started |
| M5 Playback and radio | Unified transport selection, Progressive/HLS/SABR where accessible, server policies, cancellation/limited recovery, prefetch/continuations/manual queue, 3 real full-track EOF matrix | Partial (existing Android evidence) |
| M6 Online account completion | Existing likes/dislikes, personal shelves, collections/history/sorting, playlist create/rename/edit/reorder/delete with explicit confirmation; no personal-account automated mutations | Partial (existing Android implementation, live checks pending) |
| M7 Local mode | SAF/desktop imports and persisted permissions, scanning/metadata/artwork, albums/artists/local playlists, offline playback and local radio; mode Settings and source isolation | Not started |
| M8 Preferences and Kasane Radio | Actual-time event analytics, per-track skips/context changes, local scoring/re-ranking, diversity/repeat avoidance, opt-out/clear/source-history isolation; no LLM | Not started |
| M9 Automix | Shared transition planner and audio features; genuine backend gapless/fade/overlap where supported; adaptive choice, not fixed crossfade advertised as intelligence | Not started |
| M10 Last.fm | Optional browser token/session flow with safe user credentials configuration, persistent deduplicated scrobble queue, correct listened-time threshold, Now Playing/love/unlove/logout/retry | Not started |
| M11 Linux integration and parity | MPRIS/media keys/background playback, system theme, shortcuts, drag/drop, small/HiDPI windows, installation and optional useful tray; remove old stack only after parity evidence | Not started |
| M12 Acceptance | Android + desktop tests/builds/UI/performance/security, no data regression; final manual checklist and honest status matrix | Not started |

## Chosen incremental structure

`shared/` is the portable KMP core. The first slice has commonMain and a JVM target
consumed by Android and, next, desktop: both run on JVM. No Android-specific code
is moved into commonMain. `android/app` stays the current safe Android entrypoint;
`desktopApp/` will host Compose Desktop and Linux adapters. A shared UI/platform-target
configuration follows after the domain/provider extraction; this is not yet a complete
KMP UI migration. Preserve the current working Kotlin 2.0.21/Gradle 8.9 toolchain for
this domain-only step instead of a simultaneous compiler/Room/UI upgrade.

Linux already provides libmpv and libvlc. Prefer libmpv through a narrow JNA adapter
for real transport/decoding support; verify codec/device availability before claiming
features. MPRIS is a platform port, not a second playback state/queue.

## Verification and continuation

Run from `android/`: `./gradlew :shared:jvmTest assembleDebug testDebugUnitTest assembleRelease`.
After adding desktop: include its JVM tests/build and actual isolated desktop launch,
local audio and available online full-track tests. No Cargo rebuild is required for
unchanged legacy code; do not regenerate its large target cache as a substitute for Kotlin QA.
Keep exact commands, evidence and limitations in ACCEPTANCE.md after every stage.

**Current next action:** extract the existing InnerTube request/error policy into a common client plus bounded HTTP/crypto/clock ports, port parsers to kotlinx.serialization JSON, and make Android delegate to it before wiring the Kotlin desktop catalog/playback slice. Account/library contracts are already shared. Domain extraction evidence is in `architecture/multiplatform.md`; no full-client completion is claimed.
