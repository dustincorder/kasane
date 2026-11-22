# UX research: YouTube Music, Metrolist, Kasane

## Sources
- **YouTube Music 9.46.55 on the Pixel:** could not be inspected this round. The device was on the lock screen and unlocking needs your fingerprint. Earlier work used its APK and your screenshots.
- **Metrolist:** source at `9448bb8` (2026-11-22), especially `shared/ui/screens/ArtistScreen.kt` and `api/YouTubeArtistParser.kt`.
- **Material 3:** `m3.material.io`, Android app-bar and edge-to-edge guides, plus the installed `material-3` skill.
- **Kasane:** current `feat/m3-offline` sources and real guest responses captured for artist, album and playlist pages.

## Comparison

| Area | YouTube Music | Metrolist | Kasane before | Adopted |
|---|---|---|---|---|
| Artist hero | Immersive banner with name and audience over the image | Square portrait on phones, banner on wide; image stays while content slides; top bar fades in | Giant circle, then plain banner | Wide banner when YouTube sends one, square-portrait fallback, gradient into background, scrimmed back button |
| Artist actions | Shuffle and Mix | Radio/Shuffle button group with adaptive sizes | Play + Shuffle only | Play plus YouTube's own Mix when the header provides it |
| Artist biography | Expandable description shelf | Separate About screen | Truncated to 3 lines | "Read more" dialog with full scrollable text |
| Album / playlist | Moderate cover, title, creator, stats, compact actions | Same family of detail screens | Oversized artwork and buttons | 112–168 dp cover beside facts, one wide Play, icon Shuffle/Mix/More |
| Tracklist | Numbered on albums | Numbered | Unnumbered | Numbered album rows |
| Metadata | Artist and album always named | Parsed per row | Blank artists on album tracks, whole byline as artist on radio | Parsed at source; stale cache entries invalidated |
| Radio | Artist Mix; no album/playlist radio in guest headers | Uses the same endpoints | Track radio only | Artist Mix added; no invented album/playlist radio |

## Patterns not copied
- Metrolist's parallax and video headers: nice, but they need more artwork variants than Kasane parses today.
- YouTube Music's exact button shapes and Russian/English labels: Kasane keeps its own Material 3 theme and English UI.
- Any custom recommendation ranking: YouTube's own radio and Mix endpoints are used instead.

## Material 3 audit (Kasane)
- **Color:** dynamic by default, tonal surfaces, no hardcoded per-screen accents. Good.
- **Typography:** type scale used consistently; artist name now headlineLarge over imagery. Good.
- **Shape:** medium/small corners, no giant circles for artists. Good.
- **Layout:** edge-to-edge artist imagery with scrim; album/playlist stay compact. Improved.
- **Navigation:** one shared detail screen, back gesture plus visible back. Good.
- **Accessibility:** 48 dp actions, scrimmed overlay back button, content descriptions on icon actions. Good.
- **Remaining gaps:** no true parallax collapse, no artist About page, no numbered playlists, no wide-screen two-pane artist layout beyond the existing breakpoint.
