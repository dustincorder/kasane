# Kasane UX Guidelines

Normative UX rules for both Kotlin clients (Android `:app`, Compose Desktop `:desktop`).
Consulted **before** any UI change (see `AGENTS.md`, Delegation Rule 12). Screen-level
requirements live in `docs/SCREEN_SPEC.md`; tokens and components in `docs/design-system.md`.

References used: Material 3 (m3.material.io), YouTube Music, Spotify, Metrolist, InnerTune,
SimpMusic — as patterns, not layouts to copy.

## 1. Design from user scenarios, not from the API

The user wants to *listen to music*: find something, play it, manage what's playing, keep
their library. Screens are organized around those jobs.

- **Do**: "Liked songs", "Play", "Save for offline", "Play next".
- **Don't**: expose backend entities — `CatalogKind` filter chips labelled `SONG`/`ALBUM`,
  per-resolver toggles, per-endpoint screens. If a control maps 1:1 to an API parameter,
  it probably doesn't belong in the main UI.
- One feature = one place in the UI. Playback mode, stream format, cache internals are
  implementation details; the app picks sane defaults.

## 2. No technical detail in the main UI

Never surface internals in user-facing copy: codec/container names, bitrates, `itag`,
stream URLs, resolver/source names (SABR, HLS, InnerTube), mpv version, file paths,
session/token state, request diagnostics, internal IDs.

- **Do**: "Can't play this song. Retry" / "Offline — playing saved music".
- **Don't**: "Decoded by mpv (0.38.0)", absolute `~/.local/share/...` paths, "Secret Service
  unavailable" (current desktop violations — fix on next pass).
- Diagnostics belong in logs or an opt-in "Diagnostics" block at the bottom of About —
  collapsed, plainly labelled, excluded from everyday copy.

## 3. Everyday actions stay close

Play, pause, next, queue, like, save, search — reachable in one tap/click from wherever the
user is. Rare actions move deeper (progressive disclosure: menu → sheet → sub-screen).

- Persistent surfaces: bottom nav (Android) / rail (desktop), mini-player on Android,
  player bar on desktop.
- **Don't** bury daily actions in Settings or three-level menus. **Don't** strip useful
  features for minimalism — depth ≠ deletion.

## 4. Plain, short names

Use the user's words: "Liked songs", "Saved", "History", "Devices", "Appearance".
No explaining the obvious: a Play button needs no caption; a Search field needs no
"Enter text here, then press Search". Supporting text exists to remove doubt, not to
fill space — max one line where used.

## 5. Settings: flat, grouped, honest

- Compact **flat Material 3 list** (`ListItem` rows, section headers, dividers) — not a
  wall of cards, not a dashboard.
- Sections (in order): **Account**, **Appearance**, **Playback**, **Library & storage**,
  **Devices**, **About**. Every row must answer "what does the user get".
- **No setting per technical parameter.** Ask: does a normal user need this choice?
  If not — pick a default and delete the row. Real offenders today: backend internals,
  diagnostic toggles, per-resolver switches.
- A switch needs a visible effect; a navigable row opens a real screen (never a stub).
- Account/profile is reachable from the main UI — avatar on Home top bar (Android) and in
  the rail header (desktop), not only inside Settings.

## 6. States are part of the design

Every screen ships all of: **loading** (skeleton/progress, not a blank), **empty**
(what appears here + how to add the first item), **error** (what happened + one primary
recovery action), **offline** (what still works — saved/cached music first), **denied**
(permission/feature off — how to enable), **cancel** (predictable back-out; destructive
actions undoable via snackbar where possible).

- Errors tell the user what to do, not what failed internally.
- Pull-to-refresh on content feeds (Android); Refresh affordance on desktop.

## 7. One visual language, two idioms

Same Kasane look (tokens in `design-system.md`), but desktop is not a stretched phone:

- Android: bottom navigation bar, top app bar, bottom sheets, swipe gestures, 48 dp targets.
- Desktop: navigation rail, hover states, right-click context menus, keyboard shortcuts
  (Space, Ctrl+←/→, Ctrl+F, Esc), window-adaptive two-pane layouts at ≥1200 dp,
  player bar with volume, compact 24–32 dp controls.
- Shared: track rows, artwork treatment, shelf carousels, detail-page header pattern.

## 8. Restraint in decoration

- No decorative gradients, glows, glassmorphism, oversized cards, heavy scrims or
  "ambient" background art that fights content.
- Corners from the shape scale (4/8/12/16/24); when in doubt pick the smaller radius.
- One accent color, used for: current track, progress, primary actions, selection.
  Everything else stays on neutral surfaces.
- No arbitrary count caps ("show 5 items") as UX — content flows by scenario; shelves
  scroll horizontally, lists vertically.

## 9. Motion & feedback

- Fast (150–300 ms), purposeful: tap feedback, sheet slide, progress on artwork edge.
- Every action acknowledges immediately — disabled-looking dead buttons are a defect.
- Loading that blocks interaction gets a cancel path.

## 10. Accessibility is required, not polish

- Contrast ≥ WCAG AA on all text (verified against `design-system.md` tokens).
- Icon-only actions have content descriptions; desktop actions have visible equivalents
  (hover is not the only path).
- Full keyboard operability on desktop; focus order follows reading order.
- Respect text scaling; rows grow, text ellipsizes, nothing clips.

## What these guidelines are not

Not a component library, not a mock gallery. `SCREEN_SPEC.md` defines each screen;
`design-system.md` defines how it looks. If a change can't be traced to a user scenario,
it doesn't ship.
