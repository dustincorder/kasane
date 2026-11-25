# Handoff: Lyrics + release metadata (shared KMP contracts)

Owner of shared business logic: this document is the Desktop-agent contract for
integrating the lyrics and metadata subsystems. Sources live in
`shared/src/commonMain/kotlin/cord/kasane/music/`.

## Lyrics (`lyrics/`)

- `LyricsModels.kt` — `LyricWord`, `LyricLine`, `LyricsDocument` (`syncKind`,
  `offsetMs`, `hasTiming`), `LyricsSyncKind` {UNSYCNED→LINE→WORD priority},
  `LyricsQuery`, `LyricsCandidate`, `LyricsProvider`, `LyricsStore`, `StoredLyrics`,
  `LyricsHttpTransport` (fun interface: `get(url, headers): String?`).
- `LrcParser.kt`, `TtmlParser.kt` — raw payload → `LyricsDocument`; format keys
  `"lrc"`, `"lrc-enhanced"`, `"ttml"`, `"plain"` (see `LyricsResolver.parse`).
- `LyricsResolver.kt` — `resolve(query, http, refresh)` (stored → provider cascade,
  best sync kind wins), `candidates()` for the manual picker, `choose(trackId,
  candidate)`, `adjustOffset(trackId, deltaMs)`, `parseStored`.
- `LyricsSync.kt` — `activeLine(doc, positionMs)`, `activeWord(line, positionMs,
  offsetMs)`, `seekPosition(line, offsetMs)`, `inInstrumentalBreak`.
- `LrcLibProvider.kt` — lrclib.net `/api/get` + `/api/search`. `urlEncode` is
  `internal` and reusable.
- `AmlTtmlProvider.kt` — AMLL TTML DB (CC0). Fetches `metadata/raw-lyrics-index.jsonl`
  once per instance, requires normalized title equality + ≥1 shared artist token
  (no platform ids for YouTube tracks — fuzzy hits are rejected as guesses), then
  downloads `raw-lyrics/<file>.ttml`. `supportedSyncKinds` includes WORD.
- `YtmLyricsProvider` lives in Android (`A/provider/YtmLyricsProvider.kt`) — it needs
  `OnlineCatalog`; Desktop can port it trivially (next→LYRICS browseId→browse) or
  start with LRCLIB only.

Desktop duties: implement `LyricsStore` (JSON/file), bind `LyricsHttpTransport` to its
HTTP stack, construct `LyricsResolver(listOf(LrcLibProvider()), store)`, render
`LyricsDocument` per `docs/UX_GUIDELINES.md` (sync with Gemini on the visual pass).
Behaviour: tap-to-seek uses `LyricsSync.seekPosition`; offset writes go through
`resolver.adjustOffset` so the stored value stays authoritative.

## Metadata (`metadata/`)

- `TrackMetadata.kt` — `TrackMetadata(trackId, releaseTitle, releaseDate, artistDisplay,
  genres, coverArtUrl, musicBrainzReleaseId)`, `MetadataStore`, `MetadataHttpTransport`,
  `MetadataQuery`.
- `MusicBrainzProvider.kt` — `enrich(query, http)`: recording match → earliest Official
  release → tags/genres → CAA front cover. `coverArtUrl` is null when CAA has no front
  image — never substitute guesses.
- `MetadataResolver.kt` — stored-first caching (`metadataFor(query, http, refresh)`).

Rate limit: MusicBrainz = 1 req/s; Android throttles inside its transport
(`MetadataRepository`). Desktop must do the same. Send a descriptive User-Agent.
Enrichment is gated by `settings.enrichMetadata` on Android and runs after a track is
saved (`OfflineManager.onTrackSaved`).

## Android wiring reference

- `A/lyrics/LyricsRepository.kt` — Room store + OkHttp transport + resolver.
- `A/metadata/MetadataRepository.kt` — same for metadata.
- `data/KasaneDatabase.kt` v9 — `lyrics`, `track_metadata` tables, `history.owner`.
- `ui/components/LyricsSheet.kt` — synced sheet semantics (Gemini restyles visuals).

## Likes (`provider/CloudLikes.kt` — moved to shared/commonMain)

- `CloudLikes(read, write, writeDislike?, now, dispatcher)` — per-session rating
  controller: `states` (`videoId → CloudLikeState`), `pending`, `errors`,
  `refresh(id, force)` (5-min memo, 3-permit concurrency, keeps last known state
  on failure), `seedLiked(ids)` (LM-playlist hints), `toggle`, `toggleDislike`, `close()`.
- `CloudLikeState`/`CloudLikeJsonParser` were already shared; Android keeps only the
  org.json bridge (`CloudLikeParser` in `A/provider/CloudLikes.kt`).
- Semantics (owner rule): likes are YTM ratings only — signed-in + online required.
  No local favorites table is written; offline exposes the button disabled.
  Desktop `DesktopLikes` should be replaced by this class + `CatalogFailure` mapping.
- Liked-songs list = `VLLM` playlist; after each write invalidate
  `playlistChanged(scope, "LM")`. Android filters the loaded list by
  `states[id] !in (NEUTRAL, DISLIKED)` for live unlike.

## Shared lifts already done

- `library/groupHistoryByDay` in `shared/jvmCommonMain` — generic over the entry type
  (`playedAt`, `orderKey` selectors); Android `ui/HistoryGrouping.kt` is a thin
  adapter, Desktop `library/HistoryGrouping.kt` can delegate the same way.
- `provider/PlaylistPicker.kt` in `shared/jvmCommonMain` — the full state machine
  (`open`/`refresh`/`add`/`dismiss`, lost-write → one membership read,
  `refreshRequired` on unconfirmed, no auto-repeat of remote writes). Desktop only
  needs to make its catalog layer throw `auth/SessionExpiredException`
  (now in `jvmCommonMain`, still a `java.io.IOException`) or keep the
  `CatalogFailure(SESSION_EXPIRED)` mapping.
- `offline/OfflinePolicy.kt` + `DownloadPlan` + `AudioState` in `shared/commonMain`:
  `cacheKey`, `canRequestSave`, `blockReason`, `playableFromCache(cacheKey,
  contentLength, cachedBytesFromStart)`, `CACHE_PRESETS_MB`, `customLimitMb`,
  `limitLabel`/`formatBytes`; `DownloadPlan.chunks`, `MAX_ATTEMPTS`,
  `PARALLEL_RANGES`, `MIN_RANGE_BYTES`, `BLOCK_RECHECK_MS`, `MAX_ARTWORK_BYTES`.
  Stream-type checks stayed Android-side as `StreamEligibility` (they need the
  `PlaybackStream` sealed types). Desktop deletes its copies of `mergeRanges`/`chunks`,
  the retry schedule and `blockReason` texts; the `AudioState`→`OfflineBadge`
  mapping is a 5-line `when` on each client.
- Still open for lifting: `HomeComposer` build rules, `LikedSongs`
  pagination/dedup/reconcile.

## Offline persistence

- Saved track → `OfflineManager.onTrackSaved` → enrich metadata → fetch `lyricsFor`
  (Room) → if `coverArtUrl`, `saveArtworkFrom(id, url)` downloads it to
  `filesDir/offline-artwork/<id>.img` and repoints `thumbnailUri` (official cover
  wins over the YTM thumb for saved tracks).

## Unified-behaviour rules (both clients)

- Guest history: off by default (`guestSaveHistory`), never mixed with account entries
  (`HistoryEntity.owner` = "account"|"guest"). Desktop keeps guest history memory-only.
- Likes are online-only for signed-in users; there is no local like system.
- Listen again = song grid; Song mixes = 4-track rows (semantics fixed in `HomeComposer`).

Tests: `S/jvmTest/.../LyricsParserSyncTest.kt`, `.../AmlTtmlProviderTest.kt`,
`.../MusicBrainzProviderTest.kt`.

Tests: `S/jvmTest/.../LyricsParserSyncTest.kt`, `.../MusicBrainzProviderTest.kt`.
