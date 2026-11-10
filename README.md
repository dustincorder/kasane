<div align="center">

# Kanade

**An independent music client for Linux and Android, featuring YouTube Music integration, a local audio library, personalized recommendations, and Automix.**

</div>

---

## About

**Kanade** is a modern, lightweight music player built with performance, privacy, and listening experience in mind. It unifies YouTube Music streaming with your local offline library, intelligent queue management, acoustic feature analysis, and a dedicated Automix engine.

Designed as an alternative to bloated web wrappers and generic streaming clients, Kanade provides a native, responsive interface without unnecessary background overhead or intrusive elements.

---

## Key Features

### Core Playback
- **YouTube Music Streaming** — Fast search, artist catalog browsing, and playback from YouTube Music.
- **Local Audio Library** — Scan and play local music files with offline metadata parsing and instant playback.
- **Persistent Storage** — Local SQLite database for listening history, favorites, and playlists without remote telemetry.
- **Original Dark Interface** — Focused, clean UI engineered for desktop and touch screens alike.

### In Active Development
- **Acoustic Feature Analysis** — Native DSP feature extraction (BPM, key estimation, energy curves, loudness) without cloud dependency.
- **Automix Engine** — Harmonic and phrase-aware transitions between tracks, going beyond basic crossfades.
- **Personalized Recommendations** — Context-aware track suggestions based on listening history and preference profiles.
- **Seto Assistant** — An integrated, lightweight music companion to explore and organize your library.
- **Cross-Device Sync** — Local-first library and listening state synchronization across devices.
- **Last.fm Integration** — Scrobbling and track status updates.

---

## Platforms

| Platform | Status | Audio Pipeline |
|---|---|---|
| **Linux** | In development | Native desktop audio |
| **Android** | In development | Native Media3 session & background playback |

---

## Technology

Kanade is built with a modular, local-first architecture:

- **Rust** — Core domain logic, playback pipeline, audio analysis, and database management.
- **Tauri 2** — Lightweight cross-platform native application shell.
- **Svelte 5 & TypeScript** — Reactive, modern user interface.
- **SQLite** — Local metadata, favorites, history, and cache storage.

---

## Roadmap

Kanade follows an incremental development roadmap:

1. **Foundations** — Rust workspace core, unified provider abstractions, and responsive interface.
2. **Audio Pipeline** — Gapless playback, system media integration (MPRIS on Linux, Media3 on Android), and local file scanning.
3. **Automix & DSP Engine** — Live beat grid alignment, harmonic key matching, and transition curve planning.
4. **Music Intelligence** — Offline preference profiling and recommendation provider hooks.
5. **Ecosystem & Polish** — Device synchronization and Last.fm scrobbling.

---

## Downloads

> Kanade is under active development. Public builds are not available yet.

Release packages will be published on the [GitHub Releases](https://github.com/dustincorder/kanade/releases) page once ready.

---

## Open Source

Kanade is an open-source project. Licensing details will be officially announced as development approaches its first public milestone.
