# Offline library (Android)

Three kinds of audio are kept strictly apart, in the code and in Settings:

| Kind | Where | Lifetime | Notes |
|---|---|---|---|
| Local files | not supported on Android yet | user-owned | no scanner exists in this build |
| Temporary streaming cache | Media3 `SimpleCache` (`cacheDir` or external app dir) | LRU, bounded by the user's limit (256 MB-16 GB) | automatic; replays work without the network |
| Saved music | separate `SimpleCache` in `filesDir/saved-audio`, no evictor | until the user removes it | explicit action only |

`audio_files` (Room v4) indexes both: `cacheKey = ytm:<videoId>:<itag>`, `contentLength`, `state` (cached/queued/downloading/saved/failed), `pinned`.
Bytes never live in Room.

## Behaviour
- Playback is offline-first: `TransportMediaSource` asks `OfflinePlayback.local()`; a complete copy plays through a cache-only data source with no player request and no network.
- Streamed progressive audio is written to the temporary cache through `CacheDataSource`; saved bytes are read first, then temp, then network.
- Saving runs one download at a time via `resolveForSave()` — a session progressive stream first, the verified resolver as fallback, never HLS/SABR/iOS. Only the audio track is fetched (the resolver picks an `audio/*` itag — no video payload even for music videos). Files up to 512 KB download in a single `CacheWriter` stream; larger files are fetched as up to 6 parallel Range requests assembled in a scratch file and committed span by span under a lock (concurrent writers on one cache key corrupt spans), because googlevideo caps a single connection to a few dozen KB/s — measured on-device: one 3.98 MB music-video audio track took 123.5 s sequentially vs 1.1 s ranged. A downloaded range's span survives process death (`DOWNLOADING` is re-queued at start-up); an interrupted range is re-fetched. The writer re-resolves on expired URLs (403/410), reads already-streamed bytes from the temp cache before using the network, and verifies the stored length against the declared length before marking `saved`.
- With no network the queue waits ("Waiting for a connection.") instead of burning attempts; connectivity-type failures are re-queued automatically when a network appears (`transientFailures`, kept in memory — after a process restart such rows stay `failed` until a manual retry).
- Cancel/remove deletes partial and complete bytes. Clearing the temporary cache never touches saved music.
- `Offline Only` blocks every OkHttp request (`OfflineGateInterceptor`) and makes playback fail with an explicit message instead of streaming. Search becomes a search over saved music. Downloads wait while it is on, and while a metered network is active if "Download on Wi-Fi only" is set.
- Saved tracks keep their artwork as an app-private file; favorites, history and cached catalog pages are already local Room data.

## Limits (honest)
- Only plain progressive audio can be stored. HLS and SABR deliveries are streaming-only and are reported as "cannot be saved offline"; they are never cached.
- Whether YouTube allows storing a given track is outside Kasane's control. Saved audio is app-private, never exported, and may stop working if YouTube or its terms change. The first save shows this notice.
- The saved store is always internal. The cache-location setting applies to the temporary cache and takes effect after a restart.
- Local playlists do not exist yet, so only YouTube playlists can be saved (their loaded tracks).
- Not verified on a device yet: real downloads and replays over a real network. JVM/Robolectric tests cover the full manager against real Media3 caches and Room (`OfflineManagerTest`): save→local→remove byte equality, no-network waiting, Offline Only blocking, partial downloads never offered, transient vs permanent failures, and startup re-verification of vanished bytes. Manual acceptance remains required.
