<div align="center">

<img src="assets/logo.svg" alt="Kasane Logo" width="128" height="128" />

# Kasane

**カサネ — An open-source music player for Linux and Android, featuring YouTube Music integration, with local playback, personalized recommendations, and Automix planned.**

</div>

---

## About

**Kasane** (カサネ) is a modern, lightweight music client built with a focus on performance, clean aesthetics, and respect for user control. It unifies YouTube Music streaming with your upcoming local audio library, persistent offline metadata, and an acoustic feature analysis engine.

Designed from the ground up to replace heavy web wrappers and cluttered commercial interfaces, Kasane provides responsive native interfaces for desktop and touch devices without telemetry, intrusive ads, or unnecessary background overhead.

---

## Features

### Implemented & Ready
- **YouTube Music Streaming** — Fast InnerTube-powered music search and direct stream playback.
- **Persistent Data Store** — Local SQLite storage for listening history and favorites with deduplication.
- **Native Android Audio Service** — Full background playback via AndroidX Media3 and ExoPlayer with MediaSession integration.
- **Custom Minimalist Dark Interface** — Slate/onyx design calibrated for OLED displays and high typographic contrast.

### Planned & In Development
- **Local Audio Library** — Offline audio file indexing, tag extraction, and unified local/cloud playlists.
- **Offline Stream Caching** — Automatic caching of streamed tracks for seamless offline playback.
- **Automix Engine** — Phrase-aware, harmonic mixing and BPM-matched transitions beyond simple crossfading.
- **Personalized Recommendations** — On-device preference profiling without sending listening habits to third parties.
- **Synchronized Lyrics** — Live lyrics display and translation support.

---

## Supported Platforms

| Platform | Architecture | Audio Pipeline | Status |
|---|---|---|---|
| **Android** | Kotlin, Jetpack Compose, Room | AndroidX Media3 / ExoPlayer | In active development |
| **Linux** | Rust, Tauri 2, Svelte 5, SQLite | Native desktop audio pipeline | In active development |

---

## Technology Stack

Kasane is engineered as a local-first application using platform-appropriate native technologies:

- **Android Client**:
  - **Language & UI**: 100% Kotlin with Jetpack Compose declarative UI.
  - **Audio Engine**: AndroidX Media3 (`MediaSessionService` + `ExoPlayer`).
  - **Local Persistence**: Room SQLite.
  - **Networking**: OkHttpClient with optimized InnerTube client profiles.

- **Linux Client**:
  - **Core & Audio**: Rust workspace (`vibe-audio`, `vibe-core`, `vibe-db`, `vibe-providers`).
  - **Shell & IPC**: Tauri 2.
  - **Interface**: Svelte 5 (Runes), TypeScript, and Tailwind CSS.
  - **Database**: `rusqlite`.

---

## Roadmap

Development follows a focused, iterative path:

1. **Foundations & Core Streaming** *(Current)* — Stable YouTube Music playback, clean native UI, responsive search, and persistent history.
2. **Offline & Local Library** — Device audio scanning, stream caching, and unified playback queues.
3. **Now Playing Experience** — Full-screen player sheet, gesture controls, and synchronized lyrics.
4. **DSP & Automix** — On-device beat tracking, harmonic key analysis, and smooth phrase transitions.
5. **Intelligent Curation** — Local recommendation models and smart playlist generation.

---

## Downloads

> Kasane is under active development. Official release binaries are not yet published.

Pre-built packages (`.apk` for Android, native packages for Linux) will be published under [GitHub Releases](https://github.com/dustincorder/kasane/releases) once the first milestone is complete.

---

## Open Source

Kasane is an open-source project. Licensing and contribution guidelines will be formalized as development nears its initial release.
