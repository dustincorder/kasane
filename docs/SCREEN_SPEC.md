# Kasane — Screen Specifications

Normative per-screen contract. Rules: `docs/UX_GUIDELINES.md` · tokens:
`docs/design-system.md`. Any UI change must match this spec; deviations require
updating the spec first. "States" = loading · empty · error · offline · denied · cancel.

## Global navigation model

| | Android | Desktop |
|---|---|---|
| Level 1 | Bottom nav: **Home · Search · Library** | NavigationRail: **Home · Search · Library · Settings** |
| Level 2 | Detail screens push over tabs (shared back stack), sheets over content | Detail pages inside the content pane; rail selection stays lit |
| Overlays | Now Playing (bottom sheet) > Queue sheet > Settings/Connect/Downloads | Now Playing overlay > Queue panel (right) > Connect |
| Account | Avatar → Home top bar → Profile | Avatar → top of rail → Profile |
| Back | Gesture/button pops detail → sheet → tab; never exits to launcher from level 1 | Esc / Alt+← / back button; never closes the window |

---

## 1. Home

**Purpose.** Land the user back in their listening in ≤2 taps.

**Scenarios.** Resume recent listening · pick a recommendation · browse a mood/genre chip ·
open a shelf's full page · reach search/profile.

**Hierarchy.** Top bar (app identity + avatar → Profile; Android also offline banner slot)
→ filter chips → "Listen again" shelf (from local history) → recommendation shelves.

**Actions.** Primary: play a shelf item / open detail. Secondary: chip filter, refresh,
open shelf page, avatar → Profile. Search lives on its own nav destination — no search
icon duplicating it in the top bar.

**States.** Loading: shelf placeholders. Empty: "Play something — your picks appear
here". Error: message + Retry. Offline: offline banner + saved/cached shelves only,
chips hidden. Guest: works fully; no sign-in wall.

**Android vs Desktop.** Identical content; desktop lays shelves in a wider content pane
(max ~1400 dp), scrolls vertically, hover reveals shelf item actions.

**Forbidden.** Ads-like promo cards · resolver/source labels · "powered by" strings ·
arbitrary shelf count as layout filler · duplicate search entry points.

## 2. Search

**Purpose.** Find music on YouTube Music (online) or saved music (offline).

**Scenarios.** Type → results grouped by relevance · filter by kind · open song/album/
artist/playlist · replay a recent search · offline: find saved songs.

**Hierarchy.** Search field (focused on open, debounced suggestions) → recent searches
(empty state until first query) → kind chips (*Songs · Albums · Artists · Playlists ·
Videos* — plain labels, never enum names) → results: "Top result" card then grouped lists.

**Actions.** Primary: play track / open item. Secondary: save recent, remove/clear
recent, kind filter, per-item menu (play next, queue, like, save offline).

**States.** Loading: list skeleton. No results: `"No results for 'x'"` + hint. Offline:
switch to saved-music search automatically, banner explains; don't show dead online UI.
Error: message + Retry.

**Android vs Desktop.** Android: field in top area, keyboard-driven. Desktop: field top,
Ctrl+F global shortcut, right-click menus; identical result grouping.

**Forbidden.** Showing filter chips for backend types with raw enum labels · technical
query syntax in UI · results as raw JSON-esque rows (videoId, type badges) · blocking
the keyboard with suggestions that can't be dismissed.

## 3. Library

**Purpose.** Everything the user owns or did: liked songs, history, playlists, albums,
artists, saved/offline music.

**Scenarios.** Play liked songs (incl. shuffle) · find a saved playlist · browse history ·
open downloaded music · sign in to unlock cloud collections.

**Hierarchy.** Header → segmented row/tabs: **History · Saved**; when signed in add
**Playlists · Albums · Artists** (cloud collections). **Liked songs** is a pinned system
playlist inside Playlists (opens its own detail like any playlist), not a standalone tab.
Content = flat track list or artwork grid. "Saved" (offline files) is a first-class entry,
not buried in settings.

**Actions.** Primary: play item / play-all + shuffle on lists. Secondary: like/unlike,
queue, save offline, open detail, sign-in prompt row for guest (dismissable, once per
session at list end — not a blocking wall).

**States.** Loading per tab. Empty: per-collection message ("Songs you like appear here").
Offline: cloud tabs show cached content or a clear "needs connection" state — not stale
errors. Error: message + Retry.

**Android vs Desktop.** Android: tabs row under top bar. Desktop: same tabs in content
pane; Play/Shuffle buttons inline above lists; counts in tab labels allowed
("Liked songs · 137").

**Forbidden.** Mixing cloud and local items with no provenance marker · technical IDs ·
per-collection "sync status" internals · hiding Saved behind Settings.

## 4. Now Playing

**Purpose.** Control and understand current playback; one glance = all needed.

**Scenarios.** Pause/skip/seek · like · see what's next · jump to artist/album · switch
output device (Connect) · lyrics-related actions where supported.

**Hierarchy.** Artwork (dominant, centered) → title + artist (tappable → detail) →
progress slider with times → transport row (**prev · play/pause · next**) → secondary row
(shuffle · repeat · like · device · queue) → bottom: "Up next" peek / full-width queue
button (Android) or inline queue (desktop).

**Actions.** Primary: play/pause, next/prev, seek. Secondary: shuffle, repeat, like,
device picker, queue, overflow (radio, save, share, artist, album).

**States.** Loading/buffering: artwork + indeterminate progress, controls stay live.
Error: strip above controls ("Can't play · Retry/Dismiss") — never a modal that steals
the transport. Remote (Connect): "Playing on <name>" chip replacing device icon;
same controls routed to the target. No track: sheet doesn't open; PlayerBar shows
"Nothing playing" state without dead controls.

**Android vs Desktop.** Android: modal bottom sheet, swipe-down dismiss, queue as
separate sheet. Desktop: overlay panel or expanded view, queue as right-side panel
toggleable from the bar, Esc closes, all shortcuts active.

**Forbidden.** Codec/format/bitrate labels · stream URLs · resolver names · "audio
session" internals · queue displayed as cards · decorative visualizer that adds noise
without state.

## 5. Mini-player / Player bar

**Purpose.** Persistent playback presence: see + control without losing context.

**Scenarios.** Pause from any screen · see current track · tap → Now Playing · swipe
away to stop (Android).

**Hierarchy (Android).** Strip above bottom nav: artwork · title/artist · play/pause ·
close/next; thin progress line on top edge. Swipe-down = stop & clear.

**Hierarchy (Desktop).** Full-width bottom bar: artwork+title+artist (left) · transport
center (prev · play/pause · next + shuffle/repeat compact) · right: device · volume ·
queue toggle · expand.

**States.** Hidden when queue empty. Buffering: spinner on play button. Remote:
"Playing on <name>" label instead of device icon. Offline: same (saved music plays).

**Forbidden.** Progress bar occupying a second row · more than 3 actions inside the
Android strip · auto-hiding it while something plays.

## 6. Playlists

**Purpose.** Browse saved playlists and work inside one.

**Scenarios.** Open playlist → play/shuffle · add tracks via picker · reorder/remove ·
create/rename/delete (account scope permitting) · save playlist offline.

**Hierarchy — list.** Library → Playlists tab: rows (artwork, name, count, owner).
"Liked songs" pinned first for signed-in users (matches YTM model).

**Hierarchy — detail.** Header (artwork 112–168 dp Android / 220 desktop, name, owner,
count/duration) → action row (**Play** primary, Shuffle, overflow) → numbered track list
→ footer "Add tracks" entry point.

**Actions.** Primary: Play, row tap plays from that index. Secondary: shuffle, add tracks,
per-track menu, save-all-offline, edit metadata (owner scope).

**States.** Loading header+list. Empty playlist: message + "Add tracks" action. Error:
Retry. Partial offline: saved badge per track (existing `OfflineBadges`), unavailable
tracks dimmed, not removed. Large lists: virtualized — no arbitrary cap.

**Android vs Desktop.** Same structure; desktop detail is two-pane at ≥1200 dp
(header left, tracks right). Reorder: long-press drag (Android), drag handle (desktop).

**Forbidden.** Showing playlist IDs · per-track sync internals · delete without confirm ·
hiding "Add tracks" behind overflow only.

## 7. Profile / Account

**Purpose.** Who am I, what state is my account, sign in/out/switch.

**Scenarios.** Glance at account · sign in · session expired → re-auth · switch account ·
sign out · guest → understand what sign-in unlocks.

**Hierarchy.** Avatar + name + channel line → state block (checking/signing-in/expired/
unavailable each with its one action) → primary action (Sign in / Switch + Sign out).

**Entry points.** Android: avatar on Home top bar → this screen (or Settings→Account
while Profile screen is thin). Desktop: avatar atop rail → Profile; Settings mirrors it.

**States.** Checking: progress + non-interactive actions. Signing-in: progress + what
the user must do ("finish in the window that opened") + **Cancel**. Expired: error text +
Sign in again. Unavailable: message + Retry. Guest: short value line + Sign in.

**Forbidden.** Cookie/token/session-key strings · "identity UUID" rows · Secret Service /
keystore internals · auto-retry spinners with no cancel.

## 8. Settings

**Purpose.** Rare, durable choices. If a user opens it monthly, it doesn't belong here.

**Structure (flat M3 list, section headers + dividers — no card walls):**

1. **Account** — mirrors Profile: avatar row, sign-in state and actions.
2. **Appearance** — Theme (System/Light/Dark) · Accent (Auto/palette) · *desktop only:*
   Interface scale slider + Reset + Ctrl±/Ctrl0 hint.
3. **Playback** — Autoplay; quality/network choices *only if* they map to a user-visible
   outcome ("Download over Wi-Fi only", "High data usage" — never codec names).
4. **Library & storage** — Saved music (manage/downloads) · cache size limit ·
   export local data.
5. **Devices** — Kasane Connect row → sub-screen (pairing, online devices, on/off lives
   on that screen, not duplicated as a switch here).
6. **About** — version · platform · links; opt-in collapsed **Diagnostics** for technical
   detail (never in main flow).

**Actions.** Navigable rows open real screens; switches have immediate visible effect.

**States.** Each sub-screen carries its own loading/error/offline. Settings itself must
work fully offline.

**Android vs Desktop.** Android: full-screen overlay over tabs, back to previous.
Desktop: content in the Settings rail destination; max ~720 dp column width, centered
in wide windows.

**Forbidden.** mpv/engine version strings · absolute data paths · "prototype" wording ·
Secret Service/keystore details · per-resolver/per-endpoint toggles · raw enum or
protocol names · a setting that changes nothing visible · cards around every row.

---

## Cross-screen requirements

- **Track row menu** (everywhere): Play next · Add to queue · Like · Go to artist ·
  Go to album · Add to playlist · Save offline · (Radio). Order stable across screens.
- **Offline:** global banner once (not per-screen popups); unavailable online-only
  actions hide or disable with a tooltip — never error on tap.
- **Undo:** destructive local actions (remove from playlist, unlike, delete saved)
  confirm only when irreversible; reversible ones offer snackbar Undo.
- **Counts** are informative ("Liked songs · 137"), never fake limits.
- **Provenance:** cloud vs local vs saved items stay visually distinguishable
  (badge/subtitle), per `OfflineBadges` — provenance ≠ internals.
