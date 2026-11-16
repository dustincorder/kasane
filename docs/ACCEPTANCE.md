# Kasane acceptance matrix

Statuses: **IMPLEMENTED & VERIFIED** (specified scenario and environment actually
passed), **IMPLEMENTED, NOT LIVE-VERIFIED**, **PARTIAL**, **BLOCKED**, **NOT IMPLEMENTED**.
A mock, fixture, build or static source read never proves a broader real-user flow.
The goal is the complete Android + Linux client, not only the first migration slice.

## Platform and architecture

- [x] Existing Track/catalog models and QueuePolicy extracted to portable commonMain; serialization compatibility and Android consumption verified. Richer product models/remaining business logic are not implied.
- [ ] One shared InnerTube request/parser implementation used by Android and Linux.
- [ ] Shared account, cache, library, queue/radio, preferences and settings business logic.
- [ ] Compose Multiplatform UI with Android and desktop layouts; no duplicated evolving product logic.
- [ ] Android Media3/MediaSession/notifications/audio focus/noisy route/Bluetooth preserved.
- [ ] Linux real audio backend, one queue, pause/seek/next, background lifecycle and media keys.
- [ ] Linux MPRIS, system theme, keyboard shortcuts, drag/drop, small/HiDPI windows and installation.
- [ ] Legacy Rust/Tauri/Svelte removed only after verified Kotlin parity.

## Online user scenarios (both platforms where applicable)

- [ ] First launch, existing-user upgrade and Guest Mode work without mandatory login.
- [ ] Login/cancel/channel choice/restore/expiry/logout verified; no fake successful authentication.
- [ ] Personal Home shelves, Quick Picks, Listen Again, albums/playlists/radio/categories and navigation.
- [ ] Search Songs/Videos/Albums/Artists/Playlists, suggestions/history, clear/repeat query, pagination, errors.
- [ ] Artist/album/playlist metadata and tracks, real playback and correct browse-ID routing.
- [ ] Cloud Liked Songs, playlists, saved albums, artists/subscriptions/history, filters and sorting.
- [ ] Existing account like/dislike status; Unknown distinct from Neutral, one shared state, refresh and rollback.
- [ ] Playlist open/play/add/remove/create/rename/reorder where supported/delete with explicit confirmation.
- [ ] Shared menus Play/next/queue/radio/like/dislike/playlist/artist/album/share; hide unsupported actions.
- [ ] MiniPlayer and Now Playing stay coherent, artwork/long titles/missing data/accessibility/insets/Back.

## Playback and radio matrix

- [ ] Guest and signed-in source selection, supported codec/DRM/proof validation, safe errors.
- [ ] Real HLS track from start to EOF; not only segment downloads or synthetic fixtures.
- [ ] Real SABR-only track from start to EOF via supported verification, not cold-start media.
- [ ] At least three different real compositions including a long track fully pass EOF.
- [ ] Each applicable transport: >60 s, seek both ways, pause/resume, next/previous, EOF, repeat/shuffle.
- [ ] Network loss/switch, bounded retries, stale URLs and cancellation do not reset the queue or loop forever.
- [ ] Background/system controls and route/focus loss; human confirmation for audible Pixel output at final QA.
- [ ] Single-track selection starts playback and prefills radio before end; continuation, dedup, manual items retained.
- [ ] Collection ordering, Autoplay switch, forced radio, shuffle/repeat and queue screen selection remain correct.

## Local mode, intelligence and integrations

- [ ] Local/YTM Settings switch isolates primary libraries and requires no repeated setup.
- [ ] SAF folder/file permissions and desktop import/scanning, real metadata/artwork, albums/artists/playlists.
- [ ] Local playback without internet/account; local queue, editing and local radio work.
- [ ] Shared actual listened-time analytics: start/pause/resume/seek/skip/replay/completion/ratings/context.
- [ ] Per-track skip penalties, no automatic genre dislike or 30-second implicit like; context-aware scoring.
- [ ] Kasane Radio uses real candidates, preference ranking, repeat/diversity controls; YouTube Radio remains selectable.
- [ ] Analytics opt-out, clearing and separate Local/YTM history policy, no proprietary cloud dependency.
- [ ] Real gapless/fade/crossfade; adaptive planner uses trustworthy audio features and reports backend limitations.
- [ ] Last.fm optional browser auth and protected session/user-configured credentials, cancellation/logout/expiry.
- [ ] Correct now-playing/scrobble/love/unlove, persistent ordered retry and dedup; no developer secret in binaries.
- [ ] Scrobble >30 s track and >=min(half duration,240 s) actual listened time, not play clicks or seek jumps.

## Performance, persistence and safety

- [ ] Cache-first/SWR, memory + persistent metadata, source/account/channel isolation, refresh keeps visible content.
- [ ] Single flight, cancellation, parsing off Main, pagination and limited prefetch; no recompose-triggered fetches.
- [ ] Measure first content/interactivity, image loading, HTTP calls, parse/load and audio preparation separately.
- [ ] Save tab/query/scroll and queue across navigation/rotation/background where supported.
- [ ] Existing Room data and encrypted sessions survive updates; no destructive migrations or unsafe installers.
- [ ] No secrets in new Git objects, logs, fixtures, screenshots or exports; no unauthorized account writes.
- [ ] Instrumentation only isolated `.dev` emulator; never connected tests/clear/uninstall on Pixel.

## QA evidence and final acceptance

- [ ] Shared/JVM tests, Android builds/unit/integration/Compose tests and visually inspected previews.
- [ ] Kotlin desktop build/tests, real audio/launch/desktop UI scenarios and system integration tests.
- [ ] Original YTM APK feature map distinguishes static findings from observed UI and unknowns.
- [ ] End-to-end: first launch, guest/auth, Home/Search/details, playback/radio/queue, cloud actions,
      Local/YTM switch, restart persistence and recovery from errors.
- [ ] Versioning changed only at a substantive milestone, no automatic version rollback.
- [ ] Final per-feature status matrix accurately distinguishes mocks, fixtures, real network, device and unverified work.
- [ ] One final manual list: interactive Google/Last.fm consent; personal library/actions; actual Pixel sound/media
      controls/Bluetooth; Linux audio/device integration. Do not demand per-iteration personal-account QA.

## Starting evidence (historical, not proof of KMP completion)

See `sabr-playback.md`, `android-core-reliability.md`, `docs/ui/playlist-picker.md`
(relative from repository root). Android guest radio/media-session queue and native
picker UI passed on an isolated emulator. JVM total 193 tests, 184 passed/9 live
skipped. Data-layer cache reads produced zero HTTP requests; UI timings remain
unmeasured. Full broad real-stream compatibility and real cloud writes remain
unverified; lack of audio is not mislabeled as success. Kotlin desktop is not implemented.

## Current migration evidence

Shared-domain extraction: **IMPLEMENTED & VERIFIED** for the existing Track/catalog/queue rules.
10 shared tests and 187 Android tests ran (178 passed, nine live skipped); debug/release
builds passed. Room v3 identity and generated table SQL are unchanged; isolated Android
runtime verified inserts, projections, favorite join and existing cache DB opening.
See `architecture/multiplatform.md`. Linux UI/backend, common provider and full product
acceptance remain incomplete; this is not a new release milestone.

Common account/library contracts: **IMPLEMENTED & VERIFIED** as extracted contracts
used by Android; request/parser/cache/controller migration remains **PARTIAL**.
Subsequent shared tests: 12 passed; Android tests: 178 passed, nine live skipped;
debug and release builds passed. No new claim of live account or Linux client verification.
