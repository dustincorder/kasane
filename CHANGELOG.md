# Changelog

## 0.2.0-alpha

First public alpha of the Kotlin Multiplatform architecture. Android and Compose Desktop are
the actively developed clients; the Tauri/Rust desktop is frozen and kept in-tree for
reference.

### Highlights

- **Compose Desktop Linux client** — full client on shared Kotlin logic: catalog, playback,
  library, offline, lyrics, Connect; ships as a tarball with a bundled JVM runtime and an
  `install.sh` that registers a desktop entry (no sudo).
- **Kasane Connect** — TLS device pairing, remote control (queue, seek, play/pause, volume,
  shuffle/repeat), playback transfer with position handoff and a single-performer arbiter;
  mDNS discovery fixed so Linux is visible to Android (QU-bit masking, periodic re-announce,
  rebind on network change).
- **Offline on both clients** — resumable saved tracks, listen-through cache, Offline Only
  mode, downloads UI with retry/remove; unified save policy (verified streams only).
- **Lyrics** — synchronized lyrics (AMLL TTML DB, LRCLIB, YouTube Music) with word/line
  highlighting, tap-to-seek, ±250 ms offset, manual source selection; offline lyric storage.
- **Onboarding** — two-step first run (sign-in or guest → offline/metadata preferences);
  guest mode no longer resurrects a stale session.
- **Release metadata** — MusicBrainz + Cover Art Archive enrichment for saved tracks.
- **Shared playback logic** — one queue policy (including anchored radio that does not
  restart the playing track), play-count threshold (~30 s/half-track), watch-time reporting
  and stream selection across clients.
- **Sign-in on Linux** — system WebKitGTK login window; session persisted via Secret Service;
  readiness signaling removes the dead button lock.
- **Graceful process shutdown** — child processes (verifier helper, login helper, mpv) get
  SIGTERM with an escalation timeout instead of unconditional SIGKILL.

### Known limitations

- Guest playback is best-effort (YouTube may cut anonymous streams); signed-in verified
  playback is the reliable path. SABR-only tracks are not supported.
- Connect multi-device behavior is verified in loopback; real-network validation is ongoing.
- Alpha quality: UI and provider behavior are still evolving.

## Earlier

- **0.1.x alphas** — Android client bring-up (Media3 playback, catalog, Room library),
  Tauri/Rust Linux client (frozen since), KMP migration start.
