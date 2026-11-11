# VibePlayer Implementation Roadmap

## Phase 1: Foundation & Vertical Slice (Completed)
- [x] Workspace bootstrap (Rust 1.99 + Node 26 + Tauri 2 + Svelte 5).
- [x] Definition of 6 Antigravity subagents in `.agents/agents/` and `AGENTS.md`.
- [x] Reference research on LiMusic & BitChord (`docs/architecture/RESEARCH.md`).
- [x] Unified domain models and traits (`vibe-core`).
- [x] Audio backend (`vibe-audio`) with dedicated audio thread and DSP analyzer.
- [x] Providers (`vibe-providers`) with YouTube Music InnerTube and Mock implementations.
- [x] Local SQLite persistence (`vibe-db`) for favorites and listening history.
- [x] Svelte 5 frontend with runes (`$state`, `$derived`) and original dark design system.
- [x] Views: Search, Library (Favorites & History), Queue, PlayerBar, NowPlaying Modal.
- [x] CI pipeline (`.github/workflows/ci.yml`) and passing tests/clippy.

## Phase 2: Enhanced Playback & Stream Resiliency
- [ ] Implement signature deciphering for restricted YouTube audio streams.
- [ ] Local file scanner (ID3 / Vorbis metadata extraction).
- [ ] Linux desktop MPRIS D-Bus media controls integration (`zbus`).
- [ ] Gapless playback buffer preload in `vibe-audio`.

## Phase 3: DSP Analysis & Automix Framework
- [ ] Native audio decoding pipeline for live tempo / beat grid tracking.
- [ ] Harmonic key compatibility matrix (Camelot wheel).
- [ ] Transition planner: intro/outro phrase detection and loudness normalization (EBU R128).
- [ ] Mix engine: dynamic crossfade curves.

## Phase 4: Music Intelligence & Recommendations
- [ ] User preference profiling (skip penalty, repeat score, time-of-day affinity).
- [ ] Local LLM runner (llama.cpp) & cloud AI provider traits.
- [ ] Natural language vibe generation and structured playlist proposals.

## Phase 5: Android Build & Native Media3
- [ ] Tauri 2 Android target initialization (`tauri android init`).
- [ ] Kotlin Media3 audio session integration for background playback and lockscreen.
- [ ] Mobile-optimized touch gestures and bottom sheet drawer.
