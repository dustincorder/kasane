# Playlist picker extension

Mode: Operate. This extends the existing track menu, not the Kasane visual system.

## Direction contract

THESIS: choose an actual editable YouTube Music playlist for the selected track,
then receive a confirmed result. Unknown permission or unknown membership is not
an actionable destination.

OWN-WORLD: existing onyx surfaces, Rose action tint, Sans-Serif typography and
compact music rows. No new palette, decorative cards or animations.

STORY: open Add to playlist from any shared track menu; inspect song/title and
real destinations; tap a destination once; see completion or an explicit recovery.
Guest and local-track menus do not offer this account mutation.

FIRST VIEWPORT: native modal bottom sheet with a compact song heading, close and
refresh controls, followed by a lazy list. Names wrap to two lines. Already-added
items are labeled and disabled; pending writes block duplicate taps. The native
sheet owns navigation-bar insets; content does not add duplicate system padding.

FORM: local extension. seed: none (pinned), "Сохрани существующий дизайн Kasane".

FINISH: render and inspect content and error/empty/loading states at phone sizes;
verify Back and system insets on the isolated emulator. Real account writes require
an explicit user action and remain unverified until an account test is performed.

## Executed verification

- Debug/release builds passed; 191 JVM tests discovered, 182 passed, nine opt-in
  live tests skipped.
- API 35 isolated emulator: native accessibility click, navigation-bar bounds,
  Back dismissal, duplicate-tap suppression, dismissal during an in-flight
  fixture write, and 60 Guest/account transitions passed. The first attempts
  were invalidated by a System UI ANR and text-search limitations; the final
  runner walks the Compose accessibility tree and passed after the emulator settled.
- The fixture screenshot `/tmp/kasane-playlist-picker-verified.png` was opened
  and inspected. The refresh button sits above the three-button navigation bar.
- Headless previews for loaded/loading/error picker, track menu and Now Playing
  were rendered under `android/build/previews` and visually inspected.
- No real account mutation has been attempted. Editable-playlist endpoint and
  acknowledgement handling are verified by mock transport, not a signed-in live run.
