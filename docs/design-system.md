# Kasane — Design System (normative)

Material 3, dark charcoal surfaces, one accent. Applies to both Kotlin clients:
Android `:app` and Compose Desktop `:desktop`. Source of truth in code:
`android/app/.../ui/theme/Theme.kt` and `shared-ui` theme modules.
Principles behind these tokens: `docs/UX_GUIDELINES.md` §8.

## Color tokens

| Role | Value | Use |
|---|---|---|
| Background / surface | `#0F1012` | App base |
| Surface container low/default/high | `#151619` / `#181A1E` / `#1B1D21` | Sheets, nav bar/rail, mini-player/player bar |
| Primary (`KasaneBlue`) | `#8AB6F9` | Active track, progress, play button, selected states |
| Primary container | `#1C2E47` | Selected chips, navigation indicator |
| On-surface / variant | `#D9DCE2` / `#A6ACB6` | Text, metadata |
| Outline / variant | `#3A3E45` / `#26292E` | Dividers, borders |
| Error | Material 3 `error` role | Error text, destructive markers |

Rules:
- Always use `MaterialTheme.colorScheme` roles — never raw constants — so optional
  dynamic accents work. Dynamic color recolours accent roles only; surfaces stay
  charcoal for stable contrast.
- One accent: `#8AB6F9` (or the dynamic equivalent). No secondary accent hues,
  no per-screen brand colors.
- Contrast ≥ WCAG AA for every text/background pair (blue-on-charcoal passes).

## Typography

Sans-serif only (system Sans on Android, Inter/system on desktop). Monospace is
forbidden in user-facing text — it is a debug-font smell.

| Style | Use |
|---|---|
| `headlineLarge` | Detail-page title (album/playlist/artist) |
| `titleLarge` | Screen headers on desktop, section titles on Home |
| `titleMedium` | Top-bar titles, dialog/sheet titles |
| `titleSmall` | Track names in rows, list headlines |
| `bodyMedium` | Artists, descriptions, supporting text |
| `bodySmall` / `labelMedium` | Captions, durations, shelf metadata, chips |

No custom font sizes per screen. Ellipsize (`TextOverflow.Ellipsis`) instead of
shrinking type.

## Shape, spacing, elevation

- Shape scale: **4 / 8 / 12 / 16 / 24 dp**. Track artwork 6–8 dp, cards/sheets 12–16 dp,
  dialogs 24–28 dp. When unsure — the smaller radius.
- Spacing grid: 4 dp steps; standard gutters 16 dp (Android) / 24 dp (desktop);
  list row padding 12–16 dp; section gap 8–12 dp + divider.
- Elevation: tonal only (surface-container-*). No drop shadows except the floating
  mini-player; no glows.
- Touch targets ≥ 48 dp (Android); desktop hit areas ≥ 32 dp with hover feedback.

## Shared components

| Component | Rule |
|---|---|
| `TrackRow` | 56–64 dp row: artwork, title, artist, duration; like + overflow at trailing. Current track = accent title + playing indicator. |
| `ShelfRow` | Horizontal carousel of artwork cards; title row + optional "More". Same on both platforms. |
| Detail header | Moderate artwork (112–168 dp Android, ~220 dp desktop) beside title/byline/facts; action row: **Play** (primary), Shuffle, overflow. No giant hero circles. |
| Mini-player / PlayerBar | Persistent strip above nav (Android) / bottom bar with volume + queue (desktop); thin progress line on top edge; tap → Now Playing. |
| Queue sheet/panel | Row shows drag handle only; actions via long-press/menu; swipe-to-remove (Android). "Up next" as plain list, no cards. |
| Menus & pickers | Modal bottom sheets on Android; context menus + dialogs on desktop. Never popups anchored to top-edge on Android. |
| Empty / error | Centered single message + one action button (Retry, Sign in, etc.). No illustrations unless they carry meaning. |

## Forbidden decorations

Purple/neon gradients, glows, glassmorphism, ambient background art, oversized
rounded cards, monochrome monospace labels, animated equalizers that convey no
state. If a visual element does not encode information or affordance, remove it.

## Platform adaptation

| | Android | Desktop |
|---|---|---|
| Global nav | Bottom navigation bar (Home · Search · Library) | NavigationRail + Settings entry |
| Account access | Avatar in Home top bar | Avatar atop NavigationRail |
| Secondary actions | Long-press → bottom sheet | Right-click context menu, hover reveal |
| Window adaptation | Single column; 2-pane at ≥600 dp | 2-pane detail (artwork left, tracks right) at ≥1200 dp; min window 720×520 dp |
| Player | Bottom-sheet Now Playing + mini-player | Persistent PlayerBar + Now Playing overlay + side queue panel |
| Input | Swipe, pull-to-refresh, 48 dp targets | Shortcuts (Space, Ctrl+←/→, Ctrl+F, Esc), Ctrl± scale, scroll |

---

## Appendix — legacy Rose palette (frozen Tauri client only)

The Rust/Svelte client is frozen (decision D1) and keeps its old palette — do not
port it to Kotlin targets, do not redesign under the frozen policy:

`Kasane Rose #EF5C7D` · `Kasane Crimson #D52F58` · `Kasane Wine #790F32` ·
`Wine Subtle #2B121B` · `Background #0C0D10` · `Surface #14151B` ·
`Text Primary #FFFFFF` · `Text Secondary #9E9E9E`.
