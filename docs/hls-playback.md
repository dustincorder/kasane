> Subsequent work adds an on-demand SABR/fMP4 bridge through the existing native HLS extractor. See [SABR playback](sabr-playback.md) for scope, tests and protection limitations. Historical profile observations below do not mean SABR is universally available.

# Media3 HLS playback

## Runtime

`PlaybackService` owns the same single ExoPlayer and MediaSession. Its source factory sends `kasane://track/<videoId>` items to `TransportMediaSource`. The wrapper resolves on IO and creates its child source on the playback looper. Release cancels resolution and invalidates pending callbacks.

`HlsPlayerResolver` first asks authenticated `WEB_REMIX` when the existing session is available, using only the existing origin-restricted catalog request. If no usable HLS URL is present, one anonymous `IOS` player request is made. The signed manifest is accepted only for HTTPS Google Video hosts. Current routing and its limitations are documented in [Android core reliability](android-core-reliability.md). No account credentials enter the HLS DataSource. A missing manifest no longer enables ANDROID_VR. A concrete direct authenticated WEB_REMIX format may use native progressive HTTP; otherwise the source is explicitly unavailable. Neither `OK` nor HTTP 200 alone means the track is playable.

HLS uses native `HlsMediaSource` and explicit `MimeTypes.APPLICATION_M3U8`, supporting media and multivariant playlists. Parsing, codec selection, audio decoding, buffering, EOF, and seeks belong to Media3. Video track selection is disabled in the music service. Unsupported codecs/protected content fail through Media3; no tokens or licenses are fabricated. No eager segment download or disk audio cache is added.

The parent timeline preserves the original media item, metadata, and canonical URI; signed manifest URLs are confined to the child source and never become Room track URLs. Concrete WEB_REMIX progressive sources use native HTTP ranges. The old VR DataSource is not used by the active source factory. HLS never uses that DataSource or the `?range=` mechanism.

401/403/404/410 receive no load retry. Transient load errors have at most two retries. There is no automatic mid-track switch to another profile or restart at zero. Explicit retry keeps Media3's current queue/position and can resolve a fresh source. Native retries resume the current segment/resource rather than resetting the queue.

## Profile evidence and limits

| Profile | Evidence | Kasane policy |
| --- | --- | --- |
| WEB_REMIX | Existing authenticated endpoint supports current web context. InnerTubeX catalog declares direct/HLS but GVS token requirements still apply. No user session was used for this iteration's diagnosis. | One read-only player request when signed in; only an actual HLS URL selects HLS. Authenticated playback remains unverified. |
| IOS 21.26.4 | Previous host test read all 38 HLS segments of `dQw4w9WgXcQ`; the other three samples supplied no HLS. Current InnerTubeX marks IOS broken/probe-only because of SABR-only responses and attestation limits. | One guest HLS candidate request, no assumption of catalog-wide compatibility. |
| WEB_SAFARI | Current yt-dlp notes HLS requires certain signed-in/trusted sessions; InnerTubeX directs validated paths toward SABR. Previous Kasane guest probe had no usable stream. | Not added as a speculative automatic fallback. |
| ANDROID | Previous samples had audio metadata and SABR without a direct URL. | No invented progressive/HLS stream. SABR is unsupported. |
| ANDROID_VR | Progressive fallback has documented GVS 403 beyond its initial range. | Not selected as an automatic fallback. |

Engineering references inspected: [PlaybackClientCatalog](https://github.com/MetrolistGroup/innertubex/blob/main/src/commonMain/kotlin/com/metrolist/innertubex/extraction/strategy/PlaybackClientCatalog.kt), [PlaybackClientManifest](https://github.com/MetrolistGroup/innertubex/blob/main/src/commonMain/kotlin/com/metrolist/innertubex/extraction/strategy/PlaybackClientManifest.kt), [InnerTubeExtractor](https://github.com/MetrolistGroup/innertubex/blob/main/src/commonMain/kotlin/com/metrolist/innertubex/extraction/InnerTubeExtractor.kt), [PlayerClientDirector](https://github.com/MetrolistGroup/innertubex/blob/main/src/commonMain/kotlin/com/metrolist/innertubex/extraction/PlayerClientDirector.kt), [StreamExtractor](https://github.com/MetrolistGroup/innertubex/blob/main/src/commonMain/kotlin/com/metrolist/innertubex/extraction/StreamExtractor.kt), and [yt-dlp client matrix](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py). These distinguish client capability, authentication, transport, and token requirements rather than assuming profile names guarantee playback. No GPL implementation was copied or added as a dependency.

## Isolated reproduction

The test fixture is a synthetic 440 Hz tone encoded as AAC in MPEG-TS: 75 seconds, six-second HLS segments. It contains no copyrighted recording or user data. Tests serve the assets through a loopback HTTP server inside the disposable emulator. Debug cleartext is restricted to localhost/127.0.0.1; release networking is unchanged.

```sh
cd android
./gradlew assembleDebug testDebugUnitTest assembleRelease assembleDebugAndroidTest -PkasaneHlsProbe=true
cd ..
scripts/android-hls-emulator-test.sh emulator-5580
```

The test script refuses physical serials, requires `ro.kernel.qemu=1`, checks both APK IDs are `.dev`/`.dev.test`, and uses no Gradle connected task or uninstall. The instrumentation also requires an emulator hardware identity. The test result must contain its explicit success marker; Android's shell exit code alone is insufficient.

Optional public guest test on the same isolated emulator:

```sh
$ANDROID_HOME/platform-tools/adb -s emulator-5580 shell am instrument -w -e live true cord.kasane.music.dev.test/cord.kasane.music.HlsPlaybackInstrumentation
```

This test uses only public video ID `dQw4w9WgXcQ`; it never reads the user's session. Public availability is not guaranteed. A fixture pass demonstrates native HLS support, not that YouTube currently serves the entire catalog.

## Verified results — 2026-11-12

Environment: a newly created, headless AOSP API 35 x86_64 emulator (`ranchu`, serial `emulator-5580`). No commands targeted the user's Pixel. No connected Gradle tasks were run.

The synthetic fixture was actually decoded/rendered through Media3, not just read with HTTP:

```text
PASS decoded through 61 seconds
PASS full 75-second HLS playback EOF
PASS seek backward / forward / pause / resume
PASS next track / stable identity / queue; requests=28
PASS missing.m3u8 error is bounded; queue retained
PASS segment-error.m3u8 error is bounded; queue retained
PASS network-error.m3u8 error is bounded; queue retained
HLS playback passed
```

The test asserts nonzero rendered audio-buffer count, AAC format selection, playback position at least 74.9 seconds and `STATE_ENDED`. Its first manifest URL has no extension; source selection uses the explicit HLS type. Both multivariant and media playlists are exercised. It also asserts the queue still exposes the canonical `kasane` URI, not the child manifest URI. 403 on the segment is attempted once; the dropped-connection test is limited to at most six HTTP attempts, including HTTP-layer behavior.

The first attempt correctly refused to proceed because its emulator-name check expected lowercase `sdk`; the stock AOSP model uses uppercase `SDK`. The guard was corrected to exact emulator hardware identities, then the complete test passed. The test script now also requires an explicit success marker instead of trusting the shell instrumentation exit code.

Historical result before the foreground probe fix (superseded by the full Rick Astley playback in [current core status](android-core-reliability.md)): the host's anonymous IOS player request for `dQw4w9WgXcQ` returned HTTP 200, `OK`, and an HLS URL. The same request in the emulator failed before obtaining a manifest with `UnknownHostException`. Emulator shell DNS/ping resolved the host, but that does not establish app-process DNS availability. No TLS verification, authentication restriction, or attestation was bypassed to force a pass. This is not evidence that Media3 played that YouTube track, nor that the current public HLS request received a CDN 403.

The prior Espresso, Let Down, and Beat It samples had no usable IOS HLS manifest; their full-track playback remains unconfirmed. ANDROID_VR's documented progressive GVS 403 remains unresolved for tracks without a usable alternative transport. No claim of catalog-wide 403 recovery is made.

Build checks passed together: `assembleDebug testDebugUnitTest assembleRelease assembleDebugAndroidTest -PkasaneHlsProbe=true`. The release package ID, version, signing configuration and connected-test guard remain unchanged. The four mocked safety-install tests also passed. Standard HLS support is implemented and verified; signed-in WEB_REMIX HLS, Bluetooth, background playback and MediaSession behavior have not been newly exercised in this emulator test.
