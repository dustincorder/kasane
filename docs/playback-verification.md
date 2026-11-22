# Verified YouTube playback

Kasane plays YouTube Music audio through an anonymous `WEB_REMIX` player response
and the AAC itag 140 progressive stream. YouTube requires two things for that URL:

1. **Official player-script transform** – the signature decipher and `n`
   parameter transform from the current `base.js` (`player_es6`), extracted with
   pinned `youtubei.js` 18.3.0 (`JsAnalyzer`/`JsExtractor`). Nothing is guessed.
2. **Video-bound proof** – a PoToken minted for the videoId by Google's real
   BotGuard challenge (pinned `bgutils-js` 4.0.3, MIT) and added as `pot`. A
   session-bound or emulated (jsdom) token is refused with HTTP 403 at ~1 MiB.

## Runtime

| | Browser engine | Host code |
|---|---|---|
| Android | Offscreen System WebView, `https://appassets.androidplatform.net/` origin, network loads blocked | `provider/verification/` |
| Linux | Hidden Tauri WebKitGTK window on the app's own `verifier.html`; replies accepted only from that window | `src-tauri/src/verifier.rs`, `crates/vibe-providers/src/verification.rs` |

Both load `verifier/dist/kasane-verifier.js`, built from `verifier/entry.js` with
`npm run build:verifier` (also run by `npm run dev`/`npm run build`; Android's
`preBuild` fails if the bundle is missing). The browser code never touches the
network: native code fetches `/iframe_api`, `base.js`, the BotGuard
`Create`/`GenerateIT` calls (public web API key) and the player response, then
passes data in. No user agent or origin is spoofed.

The integrity token (~12 h, refreshed 10 min early), player functions (refreshed
every 6 h or on a new player version) and proof stay in memory only. Signed URLs,
tokens and visitor data are never logged or persisted.

## Failure behaviour

Every failure is explicit: runtime version mismatch, BotGuard/integrity failure,
unsupported player script, missing itag 140, foreign or non-HTTPS audio host,
non-OK playability (reported with YouTube's status, e.g. `LOGIN_REQUIRED`), and
GVS HTTP errors from the downloader. If verification fails, the existing iOS HLS
profile is tried and the user is told while it plays ("Verified playback
unavailable (…). Playing the iOS … stream instead." – a Snackbar on Android via
`PlaybackNotices`, a status banner on Linux via `PlaybackProgress.notice`); when
both fail the user sees both reasons. Failures are also logged (`KasanePlayback`
on Android, `tracing` warn on Linux). No placeholder audio is ever produced.

Linux downloads the whole stream into memory in 1 MiB `Range` requests (YouTube
throttles single whole-file requests to about playback speed).

## Limits

- YouTube changes to BotGuard or `base.js` break playback until the pinned
  libraries are updated (`player-js-unsupported`, `verification failed`).
- This is an unofficial client using Google's attestation as a browser would;
  it is a Terms-of-Service gray area, but does not bypass or fake attestation.
- First playback waits ~1–2 s while BotGuard and the player script load.
- Verified on this project's Linux desktop build (WebKitGTK) and with Android
  unit tests; physical-device Android playback is not yet confirmed.
