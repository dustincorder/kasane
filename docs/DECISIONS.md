# VibePlayer Architecture Decisions (ADR)

## ADR-001: Modular Rust Workspace Structure
- **Date**: 2026-11-10
- **Context**: Need a maintainable codebase separating domain entities, audio output, providers, and database storage without circular dependencies.
- **Decision**: Split the project into distinct crates:
  - `vibe-core`: Pure models (`Track`, `Album`, `Artist`, `QueueState`), errors (`VibeError`), and public traits (`MusicProvider`, `PlaybackBackend`, `AudioAnalyzer`, `AIProvider`).
  - `vibe-audio`: Dedicated audio execution thread, `rodio` / `symphonia` desktop audio backend, volume control, timeline scrubber, and DSP analyzer.
  - `vibe-providers`: InnerTube HTTP client (`YouTubeMusicProvider`) and test suite mock catalog (`MockProvider`).
  - `vibe-db`: Local SQLite database for favorites, play count, and listening history.
  - `src-tauri`: Tauri 2 application shell handling IPC commands and window lifecycle.
- **Consequences**: Easy unit testing of each module independently; eliminates heavy C dependencies from the core.

## ADR-002: Rodio + Symphonia for Linux Desktop Audio
- **Date**: 2026-11-10
- **Context**: Evaluated `libmpv` (used by LiMusic) vs pure Rust audio (`rodio` + `symphonia`).
- **Decision**: Used `rodio` with `symphonia` decoders within an isolated audio thread responding over non-blocking Tokio/MPSC channels.
- **Rationale**: `libmpv` requires complex C library linking and custom packaging headers on Linux and Android, while `rodio` and `symphonia` compile portably in pure Rust.

## ADR-003: InnerTube Direct Querying with Web & Android Clients
- **Date**: 2026-11-10
- **Context**: YouTube Music does not provide a public developer API.
- **Decision**: Utilize the `WEB_REMIX` client context for search queries and metadata extraction, and `ANDROID_MUSIC` context for adaptive audio stream resolution.
- **Compliance**: No DRM circumvention or decryption algorithms are implemented. Streams are resolved from public adaptive formats.

## ADR-004: Original Minimalist Dark Theme (Zero AI Clichés)
- **Date**: 2026-11-10
- **Context**: Avoid looking like a generic AI wrapper or a direct clone of existing clients (Spotify, Apple Music, BitChord).
- **Decision**: Deep charcoal palette (`#111215`, `#17181D`, `#202228`), crisp slate typography, and a refined cobalt accent (`#3F6FD6`). No purple gradients, no fake glassmorphism, and no intrusive chat bubbles.
