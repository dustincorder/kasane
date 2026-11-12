# Android core reliability — 2026-11-13

## Playback: verified subset, unresolved catalog-wide blocker

The active `PlaybackService` no longer treats absence of HLS as permission to use ANDROID_VR. `ProgressiveStream` now contains a concrete direct audio format from authenticated WEB_REMIX; its MediaSource uses ordinary HTTP, not the VR range DataSource. Guest IOS is considered only for an actual HLS manifest. Unsupported SABR, missing formats, network failure, and player HTTP refusal are distinguished. No DRM/attestation/token is fabricated, no proxy is used, and no automatic profile loop restarts the song.

The existing GuestPlayerResolver/YouTubeDataSource APIs remain for compatibility and their regression tests, but are not wired into the active service's stream fallback.

### Exact reported track

[BLits Games — Scoutmaster Season OST: Brand New Day](https://www.youtube.com/watch?v=wzpAehzNCU8), video ID `wzpAehzNCU8`, duration 196 seconds. This is distinct from the Winter version (`NUwQ9b429_o`) and other recordings with similar titles.

Anonymous host probes using current client profiles returned:

| Track/profile | Player result | Available transport | Evidence |
| --- | --- | --- | --- |
| Brand New Day / IOS 21.26.4 | OK | SABR URL, no direct audio URL and no HLS | Confirmed both on music.youtube.com and the exact www.youtube.com endpoint used by Kasane |
| Brand New Day / WEB_REMIX guest | UNPLAYABLE | None | Host request, no account credentials |
| Brand New Day / ANDROID_VR | LOGIN_REQUIRED | None for this host request | No claim that this is the historical Pixel response |
| Rick Astley (`dQw4w9WgXcQ`) / IOS | OK | HLS | Actual full Media3 playback, below |
| Gangnam Style (`9bZkp7q19f0`) / IOS | OK | SABR, no HLS | Host request |
| Despacito (`kJQP7kiw5Fk`) / IOS | OK | SABR, no HLS | Host request |

The original Pixel failure's exact runtime transport was not logged by the previously installed release and cannot be reconstructed with certainty. Its code selected ANDROID_VR whenever HLS was absent, consistent with the known 55–60 second GVS cutoff. New diagnostics make the selected profile/transport explicit instead of inferring it from an error timestamp.

SABR/UMP extraction and playback are not implemented. A `serverAbrStreamingUrl` is not a progressive file or HLS playlist. Signed WEB_REMIX direct/HLS availability for the user's account remains unverified; authentication alone does not prove access to a working stream. Full playback of Brand New Day remains unresolved. This change does not claim a complete fix for HTTP 403 across YouTube Music.

### Actual full-track Media3 result

A dedicated headless AOSP API 35 emulator (`emulator-5580`, `ranchu`, `.dev` package) ran the real public guest Rick Astley HLS stream through the same TransportMediaSource/HlsMediaSource implementation:

```text
PUBLIC HLS Media3 started: dQw4w9WgXcQ
PUBLIC HLS full Media3 playback passed: dQw4w9WgXcQ, IOS, position=213067ms
```

The test did not seek to the end or accelerate playback. It required `STATE_ENDED`, duration over 60 seconds, and current position within 300 ms of the full duration. This is real Media3 playback, not an OkHttp segment download. Audio was muted in the isolated emulator. It establishes one real complete track, not several or the entire catalog. The earlier synthetic HLS fixture was not repeated as a substitute.

The earlier emulator UnknownHostException was accompanied by Android resolver `network access blocked` for the app UID. A debug-only, unexported blank PlaybackProbeActivity keeps the isolated live test foreground. No TLS checks, account restrictions, DRM, or attestation were weakened. Release contains no probe Activity.

### Safe diagnostics

`KasanePlayback` contains videoId, candidate profile/player status, selected transport/MIME, selected audio codec, playback position at error, load/segment start, byte offset and bytes read, HTTP refusal code, exception class, and bounded recovery counts. Successful HTTP loads are labelled 2xx because Media3's load-completion event does not expose the exact response status. No URI, signed query, cookie, response body, PoToken, authorization header, or raw network exception message is printed by these diagnostics.

Permanent 401/403/404/410 are not retried. Transient load errors have at most two retries. Network failure and unsupported transport have different safe error classifications. Explicit Retry keeps Media3's current queue/position and starts a new attempt; there is no automatic loop through profiles.

## Radio

Single-song Play starts source preparation and radio loading independently, before song completion. Explicit Start radio can override the Autoplay setting. Autoplay defaults on and is persisted in private playback preferences; it is included in the local-data export's explicit safe preference allowlist.

Radio uses the existing selected AccountSession when present, and no account headers for guests. The continuation token is retained per radio context. Single-flight/generation checks prevent late old responses from appending to a new context. New suggestions are deduplicated against the current Media3 timeline after the request, preserving items manually added while it was running. Starting another single-song radio removes only the prior generated future tail, not explicit manual items or listening history. Explicit Add to queue/Play next promotes a generated future item to manual ownership.

Collection items share a context ID, preserve their explicit order, and request radio near the end rather than on every album track. Prefetch uses actual shuffle traversal; Repeat suppresses new radio. Toggling Autoplay off cancels an automatic in-flight request. Continuation does not replace the queue. A failed radio page has at most one delayed retry (two attempts total); context/Autoplay changes invalidate late retries.

Confirmed real guest endpoint results for Brand New Day: first `next` response contained 31 unique tracks and a continuation; the next page contained 33 unique tracks without API error. Tokens and signed URLs were not printed or stored in the report.

A real PlaybackService/MediaController emulator probe used Rick Astley as seed and inserted Brand New Day manually while radio loaded:

```text
RADIO PREFETCH PASSED: queue=51 manual-preserved=true deduplicated=true before-end=true
```

This proves native service queue prefetch and preservation of the manual entry. It does not prove that all 51 tracks have playable streams. Exhausting the entire radio queue and signed-in personalized radio have not been device-tested; continuation requests and policy are additionally covered by host/mock/unit tests.

## YouTube Music ratings

CloudLikes is a shared account/channel-session-scoped store, separate from Room Favorites. It parses exact-video `likeButtonRenderer.likeStatus` and target-scoped toggle endpoints. A real anonymous `next` response for the reported video contained `playerOverlays.playerOverlayRenderer.actions[].likeButtonRenderer` with that exact target and INDIFFERENT; signed-in LIKE/DISLIKE variants are tested with mock transport.

States are Unknown, Loading, Liked, Disliked and Neutral. Unknown is not shown as an empty neutral heart. All track rows, Now Playing, queue and shared menus consume the same store. Local Favorites explicitly retain their independent local operation. Unknown/loading states and current ratings have accessibility descriptions.

The store uses per-video single-flight, at most three concurrent reads, and a five-minute memory TTL. Opening Now Playing forces a justified refresh. Room-cached VLLM metadata supplies additional known-liked hints; it does not trigger a full Liked Songs download for each heart. An authoritative returned Neutral/Disliked status wins over cached hints; network failure/unknown server metadata do not erase a known cached hint. Different video IDs are never merged by title.

Like/Unlike and Dislike/Remove dislike use authenticated exact-video operations only on a user click. Updates are optimistic, repeated presses are blocked while a mutation is pending, and errors restore the preceding state and emit a message. Logout/account/channel changes dispose the previous store and cancel its requests. No real account mutation was performed by the agent. Actual authenticated rating reads/writes still require user-account verification; mock HTTP acknowledgement is not proof of server-side account changes.

## Context menus and library

The same TrackContextMenu is used from track-row overflow/long press, Home carousel cards, Search, Now Playing, Up Next, Library tracks and catalog detail tracks. Available actions: Play, Play next, Add to queue, Start radio, cloud Like/Unlike, cloud Dislike/Remove dislike, a separately labelled local Favorite operation, canonical-link Share, and Go to artist/album when an actual browse endpoint for that video is known. Navigation never guesses an artist/album ID from a title. Missing metadata hides those actions. These public related browse IDs are held in a bounded in-memory map; cached pages without such IDs may lack navigation until fresh metadata arrives.

Add to playlist is not implemented and is hidden. There is no fake playlist chooser or simulated successful edit. Native Material3 DropdownMenu handles dismiss/Back/window constraints; the container uses the existing Kasane surface token. The header wraps long titles, actions retain native touch targets, and menu opening does not change playback. Physical-device navigation-bar and touch behavior still need manual verification.

Existing account-scoped Room metadata cache, cache-first Library and pull-to-refresh remain in use. Tests exercise separate Playlists, Saved Albums, Subscriptions and playlist-content browse calls with an authenticated mock provider. The canonical VLLM shortcut supplements real returned collections rather than replacing them. No actual personal library was read in this iteration; real category loading remains to be checked on the user's session. Local Favorites/History and account credentials were not cleared.

## Verification boundaries

Gradle debug/release and JVM checks, preview rendering, and diff validation are recorded with the completion report. All instrumentation was explicit `.dev` instrumentation on the isolated emulator; no connected Gradle tests or install/uninstall/clear operations targeted the user's Pixel. No real user-account likes, dislikes, playlists or collections were modified. Version/signing and release application ID were unchanged. The remaining primary blocker is full-track availability for SABR-only content, including the reported Brand New Day video.

### Completed checks

- `./gradlew assembleDebug testDebugUnitTest assembleRelease`: successful after the final source review.
- JVM: 151 discovered tests, 142 passed, 9 opt-in/live tests skipped, no failures/errors. The separately executed real public playback and radio probes above are not counted as JVM passes.
- `assembleDebugAndroidTest -PkasaneHlsProbe=true`: instrumentation APK compiled for the `.dev` target.
- Four mocked safe-install script checks passed; no physical-device installation was performed.
- `git diff --check`: clean.
- Six Compose previews rendered and opened: HomeScreenPreview, ProfilePreview, NowPlayingPreview, TrackRowPreview, UnknownCloudLikePreview and TrackContextMenuPreview. PNGs are under `android/build/previews/cord/kasane/music/ui/`; they are local build artifacts, not published binaries. The expanded menu preview shows wrapped title, full action list and distinct source labels. Physical system-bar behavior remains unverified.
