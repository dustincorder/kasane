# YouTube Music (Android) UX research, Nov 2026

## Method
- **App:** YouTube Music (Morphe build) on a Pixel 6 Pro, 1440×3120, Russian UI.
- **Driven over ADB:** taps, swipes, `uiautomator dump`, screenshots.
- **What was exercised:** an artist page (Kim Petras), an album (Detour), a personal playlist, Now Playing, the queue, the track, album and playlist menus, and Library. Playback was started and paused; no account data was changed.
- Screenshots are not committed because they show personal library content.

## Artist page
- **Hero image:** a full-bleed photo runs behind the status bar, about 40% of the screen tall, and fades to the background colour. There is no solid toolbar.
- **Overlaid controls:** Back, Samples, Search and More sit directly on the image as plain icons without circles. Contrast comes from the dark top of the gradient.
- **Header layout:** a large bold left-aligned name, then monthly listeners, then one row of actions.
  - Left: a pill "Subscribe 655K".
  - Right: a small round **Mix** button and a large white round **Play** button.
  - Play sits on the right, in thumb reach for right-handed use.
- **Collapse:** as the page scrolls, the image fades out and the artist name moves into the top bar. The Music/Concerts tabs stay pinned under the top bar.
- **Sections:**
  - Top tracks: 4 rows, each with a menu, and "See all". The next page peeks in from the right as a horizontal pager of columns.
  - Albums, then Singles & EPs: square cards with the year.
  - Videos: 16:9 cards.
  - Fans also like: round avatars.
  - About.
- **About:** shows total views and a description preview with a **More** button that expands it inline. **Collapse** folds it back. It is not a separate screen.
- **Tapping a top track** starts playback and opens the full player immediately. The queue source becomes the artist name.

## Album page
- **Background:** tinted by the artwork, fading to black.
- **Above the cover:** a small artist chip (avatar and name, tappable), then the comment count and year.
- **Cover:** a centred square about 43% of the width, with the bold centred title below.
- **Action row:** Download, Save, a **large white circular Play** in the centre, Comments and More. Shuffle is not a top-level button; it is inside More.
- **Tracklist:**
  - Rows are numbered and have no artwork.
  - Each row shows the title, then an explicit badge, artist, duration and play count, plus a menu.
- **On scroll:** the title moves into the top bar, and a **floating Play FAB appears at the bottom right** once the header Play scrolls away.
- **More menu:** Shuffle, **Start mix** (album radio), Play next, Add to queue, Add to playlist, Go to artist, Share, Pin to speed dial.

## Playlist page
- **Same template as the album**, so the two are consistent with each other. Differences:
  - The cover is a 2×2 collage.
  - The owner is shown as a chip (avatar and name).
  - The stats line reads: views • duration • last updated • visibility.
  - The description is shown inline.
- **Owned playlists** replace Save with **Edit** and add an "Add track" row at the top of the list.
- **Rows** show artwork, not numbers.
- **More menu:** Shuffle, Sort, Find in playlist, **Start mix** (playlist radio), Add to queue, Add to playlist, Delete (owned only), Pin.

## Player
- **Mini-player:** artwork, title and artist, then Previous, Play/Pause and Next. Tapping it opens the full player.
- **Top bar:** Collapse at the left, an Audio/Video toggle segment in the centre, and More at the right.
- **Title area:** the left-aligned title (with a chevron) and artist sit **below** the artwork. Below them is a scrollable chip row: like/dislike with counts, Lyrics, Comments, Save.
- **Controls:** the seek bar, then Shuffle, Previous, a big Play, Next and Repeat, all in the bottom third of the screen.
- **Queue:** a bottom handle labelled with the queue source ("Mix: I Like Ur Look"). Dragging it up turns the player into a mini header with the queue below, showing "Source: Kim Petras" and a Save button.
  - Each row has artwork, title, artist and duration, and a drag handle.
  - The current item is highlighted.
- **More menu:** Start mix, Add to queue, Save, **Go to album**, **Go to artist**, Credits, Pin, Clear queue, Report, Sleep timer. The top of the same sheet has quick tiles (Play next, Add to playlist, Share) and a like button.
- **Menus everywhere:** bottom sheets with a header row (title and subtitle, like, close), a row of 2–3 large quick-action tiles, then a list.

## Adopt / adapt / avoid for Kasane
- **Adopt:**
  - Full-bleed artist hero with a fade and the name collapsing into the top bar.
  - Play as the dominant control and Mix as a secondary round button.
  - Inline "More/Collapse" for the biography.
  - Numbered album rows against artwork playlist rows.
  - One template shared by album and playlist pages.
  - Start mix in the album and playlist menus.
  - The queue source label.
  - Go to album and Go to artist in the player menu.
- **Adapt to Material 3:**
  - Overlay icons on the hero should get a scrim or a tonal container when the image is light. YouTube relies on the image being dark, and Material 3 contrast rules need a guarantee.
  - The floating Play button after scrolling should be a Material 3 FAB anchored to the thumb zone.
  - Quick-action tiles at the top of menus should be tonal buttons in a row.
  - Tabs pinned under a collapsed header map to a TopAppBar scroll behaviour plus a sticky header.
- **Avoid:**
  - Comment counts and comment entry points (Kasane has no comments).
  - Hiding Shuffle entirely in a menu on albums. Kasane already exposes it compactly.
  - Plain icons over arbitrary images with no contrast guarantee.
  - The Audio/Video toggle (Kasane is audio-first).

## Metrolist (source at `9448bb8`, 2026-11-22)
- **Artist page** (`shared/ui/screens/ArtistScreen.kt`):
  - Uses `BoxWithConstraints` to pick a square portrait on narrow screens and the banner on wide ones.
  - The image stays put while the content slides over it.
  - The transparent `TopAppBar` interpolates icon contrast and shows the title only after the hero has scrolled away.
  - Shuffle and Mix are separate actions, and each shelf is kept as its own section.
- **Artist model:** banner, description, monthly listeners, view count, radio params and subscription state, all parsed from the immersive header.
- **Device Connect** (`docs/device-connect.md`) informs the design of Kasane Connect:
  - The audible device owns playback.
  - Pairing is mutual, with the same code approved on both devices.
  - Discovery never moves playback.
  - A failed transfer restores the original position.
  - Unauthenticated connections are capped.
  - Indexed queue commands are checked against a revision.
  - mDNS is limited to real LAN interfaces. Metrolist measured runaway CPU when virtual adapters were included.

## Material 3 notes used
- **Edge-to-edge:** draw the hero behind the status bar and consume insets once, at the scaffold.
- **Collapsing header:** use the TopAppBar `exitUntilCollapsed` or `enterAlways` behaviours, with the title appearing only once the hero has gone.
- **Touch targets:** at least 48 dp.
- **Contrast on imagery:** overlaid icons need a scrim, or a tonal icon-button container, whenever contrast with the image is not guaranteed.
- **Primary action:** at most one prominent action per screen region, with a FAB in the thumb zone for the persistent primary action.

## Downloads and quality settings (YouTube Music, no Premium; read-only look)
- **Library → Downloaded chip:** filters the library to downloaded playlists, tracks and albums. With nothing downloaded it shows an empty state with a "Find music" action. A gear icon in this view opens "Downloads & storage".
- **History:** a History button sits in the Library top bar, next to Search and Account. It is not a section on Home.
- **Settings → Downloads & storage:**
  - Storage bar (free and used space) and a "Saved files" screen.
  - "Download only over Wi-Fi".
  - **Audio quality**: Low / Normal / High.
  - Video quality.
  - **Smart downloads**: automatically keeps up to 200 recently played tracks.
  - "Delete all downloaded content", behind a confirmation dialog.
- **Settings → Data saving:** separate **streaming** audio quality for mobile data and for Wi-Fi, each Low / Normal / High / Always high. "Stream only over Wi-Fi" is also here.
- **Settings → Playback:** equalizer (system), start playback from external devices, double-tap seek length, dynamic queue.
- **Account sheet:** channel, Downloads, History, Recap, Switch account, Settings.

### For Kasane
- **Quality:**
  - Separate a **download quality** preset from **streaming quality**. Streaming quality can be set per network type (mobile / Wi-Fi).
  - Presets: Low ≈ Opus 50k (itag 249) or AAC 48k (139); Normal ≈ AAC 128k (140); High ≈ Opus ~160k (251), with AAC 140 as the fallback where Opus can't be decoded.
  - Premium-only formats are never offered.
- **Downloads:**
  - Albums and playlists get a Download action with progress, and per-row downloaded indicators.
  - "Downloaded" becomes a Library filter.
  - "Wi-Fi only" and "Delete all downloads" (with confirmation) go in Settings, along with real storage figures.
- **Library:** add a History entry point to the Library top bar. The Home "recently played" section becomes optional.
