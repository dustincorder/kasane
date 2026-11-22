# Android roadmap and technical specs (phases 3-7)

Status key: done = built, tested, installed; foundation = tested library code, not user-visible; spec = design only.

| Phase | Status | Where |
|---|---|---|
| 1 Material 3 UI | done | `ui/`, `ui/theme/Theme.kt` |
| 2 Offline library | done (device behaviour unverified) | `offline/`, `docs/offline-library.md` |
| 3 Import / export | foundation | `library/`, `shared/.../library/`, `docs/kasane-export-format.md` |
| 4 AutoMix | foundation | `shared/.../mix/` |
| 5 Local AI DJ | spec | below |
| 6 High-quality audio | spec | below |
| 7 Kasane Connect | spec | below |

## Phase 4 - AutoMix
Done: `BeatAnalyzer` (tempo within 1 BPM on synthetic 76-174 BPM clicks, beat grid within 30 ms, rejects silence/noise), `Crossfade` (equal-power, level match capped at +6 dB, soft limiter), `TransitionPlanner` (beat-aligned only when both analyses are confident and tempos are within 4%, otherwise a plain equal-power fade, otherwise null = normal transition).
Missing, in order: (1) key/chroma and energy/intro-outro features (needs an FFT); (2) a decode-to-PCM analysis job over cached/saved audio, run only on charging, cool, unmetered devices, results in Room; (3) **real dual-track playback**: a second ExoPlayer preloading the next item with a custom `AudioProcessor`/sink mixer that applies `Crossfade.mix` to both decoded streams; the existing single-player `PlaybackService` cannot mix, and cover animation must be driven by the mixer's fade position (never by a timer). Until (3) exists AutoMix must stay off; nothing in the UI pretends otherwise.
Cover motion spec: fade position `t` in 0..1 from the mixer drives outgoing cover `translationX = -t*w*0.35, scale = 1 - 0.25t` and incoming `translationX = (1-t)*w*0.35, scale = 0.75 + 0.25t`.

## Phase 5 - Local AI DJ
Recommendation engine is separate from chat: features (tempo, energy, tags from analysis, history/favorites vectors) -> cosine/ALS-style scoring in Room, usable with no LLM. Optional quantized model (llama.cpp-class, GGUF, ~1-3B) is a user-initiated download, never in the APK, run only when battery > 30 %, not charging-hot (`PowerManager.thermalStatus < MODERATE`). The LLM only maps text to a typed intent (`find`, `playlist`, `queue-add`, `explain`); queue changes need confirmation. Catalog lookups use the existing InnerTube client. No external AI API, history never leaves the device.

## Phase 6 - High-quality audio
Audit result for this build: Media3 1.5.1 ExoPlayer decodes MP3, AAC/M4A, Opus, Vorbis, FLAC, WAV through platform codecs plus its built-in extractors (FLAC, Ogg, MP4, WAV, MKV). ALAC and AIFF are not guaranteed by `MediaCodec` on all devices. Plan: keep platform decoders; add the Media3 FFmpeg decoder extension only if ALAC/AIFF on the target phones fail (LGPL, +4-8 MB/ABI, must be built from source, so audit before adding). ReplayGain via `LoudnessEnhancer`/gain `AudioProcessor`. Show codec/bitrate/sample rate from `Format`. Do not claim bit-perfect: `AudioTrack` goes through Android's mixer unless USB exclusive/offload is verified per route.

## Phase 7 - Kasane Connect
Reference only: the discarded commits (`backup/pre-devin-integration`, `536c833 feat(connect)`). Do not restore. Design: mDNS discovery (`NsdManager`), pairing by short code + TLS certificate pinning (Ed25519 identity per device), a minimal command channel (state, play/pause/seek, queue handoff), then library sync via the Kasane export format. Desktop work starts only after Android phases are stable.
