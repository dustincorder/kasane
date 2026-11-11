# Kasane (カサネ) Agent Coordination Guidelines

This document establishes the collaboration contract between the lead developer (coordinator) and the specialized subagents working on Kasane.

## Project Identity

- **Name**: Kasane (カサネ)
- **Android ID**: `cord.kasane.music` (Kotlin + Jetpack Compose + Media3)
- **Desktop ID**: `cord.kasane.desktop` (Rust + Tauri 2 + Svelte 5)
- **Palette**: Dark onyx/slate base with signature Kasane Rose (`#EF5C7D`), Crimson (`#D52F58`), and Wine (`#790F32`) accents.

## Agents Directory

| Agent | File | Domain |
|---|---|---|
| **architecture** | `.agents/agents/architecture.md` | Workspace structure, crate boundaries, trait definitions, performance, memory safety |
| **music-provider** | `.agents/agents/music-provider.md` | YouTube Music (InnerTube), stream resolution, search, metadata, local audio scanning |
| **audio-engine** | `.agents/agents/audio-engine.md` | Playback control, queue, rodio/cpal backends (desktop) & Media3 (Android), DSP analysis, Automix |
| **intelligence** | `.agents/agents/intelligence.md` | Local/cloud AI recommendation providers, preference profiles, structured JSON outputs |
| **interface** | `.agents/agents/interface.md` | Svelte 5 (desktop) & Jetpack Compose (Android), design tokens, typography, UX |
| **quality** | `.agents/agents/quality.md` | Test execution, clippy checks, TypeScript checks, Compose preview rendering, CI pipelines |

## Coordination Rules

1. **Selective Invocation**: Never invoke all agents simultaneously for trivial changes. Invoke specialists strictly aligned with the task scope.
2. **Single-Writer Discipline**: Multiple agents must never edit the same files concurrently. Work should be segmented cleanly across crates, Android packages, or frontend modules.
3. **No Unchecked Assumptions**: Code is never marked complete just because it compiles without errors. Tests must be executed and verified by `quality`.
4. **Clean Design Principles**: Avoid AI clichés (purple glows, faux-glass, omnipresent chat widgets, monospace UI labels). Kasane is a professional, high-performance music player first and foremost.
5. **Architectural Guardrails**:
   - Zero `unwrap()` on runtime paths.
   - Core playback must never depend on an AI provider or cloud connection being available.
   - Local-first architecture: full offline functionality for local tracks and cached metadata.
   - Single source of truth: refer to `docs/agent-map.md` before navigating the codebase.
