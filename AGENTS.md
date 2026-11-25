# Kasane (カサネ) Agent Coordination Guidelines

This document establishes the collaboration contract between the lead developer (coordinator) and the specialized subagents working on Kasane.

## Start here

Read `CLAUDE.md` first: rules, documentation map and mandatory checks. Current state of the code is in
`docs/ARCHITECTURE.md` and `docs/FEATURE_MATRIX.md`; `docs/agent-map.md` is outdated.

## Project Identity

- **Name**: Kasane (カサネ)
- **Android ID**: `cord.kasane.music` (Kotlin + Jetpack Compose + Media3)
- **Desktop ID**: `cord.kasane.desktop` (Compose Desktop, KMP — целевой клиент по решению владельца; Rust + Tauri 2 + Svelte 5 заморожены, не развивать и не удалять)
- **Palette**: Android uses dark charcoal surfaces with a cold-blue accent (`KasaneBlue` `#8AB6F9`, Material 3, optional dynamic accents; see `docs/design-system.md`). The Linux desktop still uses the legacy Kasane Rose palette until its redesign.

## Agents Directory

| Agent | File | Domain |
|---|---|---|
| **architecture** | `.agents/agents/architecture.md` | Workspace structure, crate boundaries, trait definitions, performance, memory safety |
| **music-provider** | `.agents/agents/music-provider.md` | YouTube Music (InnerTube), stream resolution, search, metadata, local audio scanning |
| **audio-engine** | `.agents/agents/audio-engine.md` | Playback control, queue, rodio/cpal backends (desktop) & Media3 (Android), DSP analysis, Automix |
| **intelligence** | `.agents/agents/intelligence.md` | Local/cloud AI recommendation providers, preference profiles, structured JSON outputs |
| **interface** | `.agents/agents/interface.md` | Svelte 5 (desktop) & Jetpack Compose (Android), design tokens, typography, UX |
| **quality** | `.agents/agents/quality.md` | Test execution, clippy checks, TypeScript checks, Compose preview rendering, CI pipelines |

### Devin Local subagent profiles (`.devin/agents/`)

Native Devin subagents, spawnable via `run_subagent` by profile name. Domain specialists above
describe areas of the codebase; the profiles below are workflow roles.

| Profile | File | Model | Role |
|---|---|---|---|
| **developer** | `.devin/agents/developer.md` | `swe-2-max` (Free) | Feature implementation, shared KMP logic, platform integrations |
| **debugger** | `.devin/agents/debugger.md` | `claude-sonnet-5-5-medium` | Defect reproduction, root-cause analysis, regression tests, targeted fixes |
| **designer** | `.devin/agents/designer.md` | `gpt-6.1-sol-medium` | Visual design on Android/Desktop, component consistency, AI-slop removal |
| **ux-reviewer** | `.devin/agents/ux-reviewer.md` | `gpt-6.1-sol-medium` | Read-only audit of user flows, navigation, accessibility, UI states |

The six `.agents/agents/` profiles above are Antigravity/Codex definitions: their `subagent:`,
`mainAgent:` and `tools:` frontmatter keys are not Devin's and are ignored (with warnings in
`devin doctor`). Devin still loads them as subagent profiles — on the router-selected default
subagent model with unrestricted tools. Keep them for Antigravity/Codex compatibility; for
Devin delegation prefer the native `.devin/agents/` profiles, which pin models and tool sets.

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

## Delegation Rules (Devin subagents)

1. **The lead agent owns integration and final verification.** Subagents return findings and
   changes; the coordinator merges them, resolves cross-cutting concerns, runs the mandatory
   checks (`scripts/verify.sh` subsets), and reports to the user. A task is not done until the
   coordinator has verified the combined result.
2. **Delegate only when needed.** Spawn a subagent when the task clearly matches its profile
   and benefits from a fresh context. Trivial, single-file or purely conversational work is
   done directly. Never fan out all four profiles on every task.
3. **No parallel writes on the same files.** Concurrent subagents must own disjoint file sets
   (e.g. `shared-ui/` vs `desktop-compose/`). If scopes collide, run them sequentially.
4. **ux-reviewer never edits code.** It is read-only (no write/exec tools): it audits and
   returns prioritized recommendations. Implementation of its findings goes through
   `developer`/`designer`.
5. **designer never changes business logic.** Presentation only: composables, tokens, layout,
   motion, UI states. View models, repositories, playback, networking, database and protocol
   code are out of scope.
6. **debugger never redesigns.** Reproduce, isolate, regression-test, fix minimally. Visual or
   UX problems found along the way are reported, not restyled.
7. **Check Android ↔ Compose Desktop consistency.** Any change in `shared/`/`shared-ui/` — or a
   feature implemented on only one Kotlin target — must be explicitly checked for parity on the
   other, and the result stated in the report.
   - **A feature is not product-complete on a single client.** It counts as done only when it is
     integrated on both Android and Compose Desktop, or carries a documented platform limitation.
   - New business logic (providers, parsers, data models, policies) lands once in
     `shared/commonMain`; shared UI components land in `shared-ui`; only platform integration
     (storage, playback, windowing) lives in the clients. The lead agent owns shared contracts;
     desktop integrations consume them — never fork parsers or models into a client module.
8. **The Rust desktop is frozen.** Per resolved decision D1 (`docs/ROADMAP.md`), KMP is the
   target architecture: `crates/`, `src-tauri/` and the Svelte `src/` are not developed and not
   deleted — no feature work or refactoring from any agent. Reading Rust code (e.g.
   `kasane-connect`, for protocol parity) is allowed.
9. **No uncontrolled nesting.** Profiles do not set `max-nesting`; subagents cannot spawn their
   own subagents. Only the lead agent delegates, and it tracks every spawned agent to completion.
10. **Cost control.**
    - **Free subagents may be spawned automatically.** Only `developer` is confirmed free —
      SWE-2 Max is marked Free in this Devin install. The lead agent may delegate to it
      without asking when the task warrants it.
    - **Unknown billing requires permission.** Router-default profiles (`subagent_explore`,
      the legacy `.agents/agents/` specialists), `subagent_general` (inherits the parent's
      model), and the pinned `debugger`/`designer`/`ux-reviewer` profiles are not confirmed
      free — treat them as paid until verified. Before spawning one, the lead agent announces
      the model, the task and the reason for delegation, and proceeds only after the user's
      approval.
    - **No automatic fallback to a paid or unverified model.** If a confirmed-free option
      cannot do the job, the lead agent stops and asks — it never silently substitutes a
      costlier profile.
    - Minimize spend regardless: prefer one well-scoped delegation over fan-out, and resume a
      completed subagent instead of spawning a duplicate.
11. **Report failed delegations.** If a subagent cannot do its job (denied tools, unavailable
    model, missing inputs), the lead agent says so to the user instead of silently absorbing
    the task. A delegation that failed is reported as failed; doing the work itself is a
    separate, explicit decision — never a quiet substitution.
12. **UX spec is normative for UI work.** Before changing or reviewing any interface, agents
    must consult `docs/UX_GUIDELINES.md`, `docs/SCREEN_SPEC.md` and `docs/design-system.md`.
    UI is designed from user scenarios, not from API structure: no internal statuses, codecs,
    resolvers, IDs or diagnostics in the main UI; everyday actions stay one tap/click away.
13. **User-scenario verification is mandatory.** Successful compilation, passing unit tests and
    the mere presence of an implementation do not prove a feature works. Before reporting done,
    exercise the real user scenario — run the app, `./gradlew :desktop:run --smoke`/`--render`
    for Compose Desktop, a device/emulator flow for Android — or state explicitly which part of
    the scenario could not be exercised and why.

## Build notes
- Desktop release binary: `npx tauri build --no-bundle` → `target/release/kasane`. A plain `cargo build --release -p kasane` omits Tauri's `custom-protocol` feature, so the binary loads the dev server (`localhost:5173`) and shows "Connection refused".

## Compose desktop (target client, branch `exp/compose-desktop`)

- Gradle project `:desktop` (`desktop-compose/`), built from `android/`: `./gradlew :desktop:run`,
  `:desktop:test`, `:desktop:createDistributable`. Needs `mpv` at runtime.
- Headless checks: `--smoke`, `--connect-smoke` (set `KASANE_RUST_CLI` for Rust interop), `--render DIR`.
- Details and parity status: `docs/architecture/compose-desktop.md`. The Tauri client is unaffected.
