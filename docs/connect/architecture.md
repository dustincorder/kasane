# Kasane Connect — architecture decision

## Context
- Android: Kotlin/Compose. Playback lives in `PlaybackService` (Media3 ExoPlayer + MediaSession). The UI talks to it only through `PlaybackManager`, a `MediaController` projection, and Media3 owns the queue.
- Linux: Tauri 2 + Rust. `kasane_audio::PlayerManager` owns the queue and the rodio backend. Svelte polls it through Tauri commands, and YouTube streams are resolved by `kasane_providers::YouTubeMusicProvider` with the WebView verifier.
- `shared/` (KMP) has no live JVM desktop consumer any more. The Kotlin/Compose desktop that the old Connect targeted (`backup/pre-devin-integration`, `536c833`, `6e6ec6d`, `b79f526`) was replaced by Tauri.

## Old implementation
Newline-JSON over TLS, a 6-digit code typed on the controller, an HMAC proof keyed by that code, session tokens, sequence numbers, jmdns and BouncyCastle certificates. It is not restored. Reasons:
- **Security:** an active attacker relaying the pairing can brute-force a 6-digit HMAC key offline in milliseconds.
- **Desktop mismatch:** its desktop side drove mpv from Kotlin, which no longer exists.

What carries over is the overall shape: TLS, newline-JSON, a player-owns-state model, metadata-only track projection, handoff that the target resolves locally, and command sequencing.

## Decision
- **One protocol, two implementations.** It is specified in `protocol.md` and checked by the shared `test-vectors.json`.
  - Kotlin: `android/app/src/main/java/cord/kasane/music/connect/`, with no new heavy dependencies.
  - Rust: new crate `crates/kasane-connect`, using rustls, rcgen and mdns-sd.
- **Transport:** TLS 1.3 with mutual self-signed ECDSA P-256 certificates. Trust is a pinned SHA-256 certificate fingerprint per paired device.
- **Pairing:** numeric-comparison SAS with a commitment (Bluetooth/ZRTP style). Both screens show the same 6 digits and the user confirms on both. A relaying attacker cannot make the codes match.
- **State ownership:** every device is the sole authority over its own playback. Controllers render that device's snapshots read-only and send commands. There is no merged or shared queue.
- **Discovery:** mDNS `_kasane._tcp`, with manual `host[:port]` as fallback. mDNS TXT records are hints only; identity always comes from the TLS fingerprint.
- **Handoff:** push a metadata-only queue and position to the target. The target resolves media with its own provider and acknowledges. The source pauses only after a positive acknowledgement.
- **Never on the wire:** cookies, OAuth/session tokens, resolved stream URLs, `visitorData`.
