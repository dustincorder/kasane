# One-handed use (Android)

Principles, checked against Material 3 touch-target guidance (48 dp minimum) and Android's gesture/back guidance:

1. **Symmetric, not right-handed.** Controls span the width (or sit centred) so left and right thumbs reach them equally. Nothing was moved to one edge.
2. **Primary action = widest control**, in the middle third of the screen (Play on artist/album, play/pause in the player).
3. **Frequent actions low, rare actions behind a menu.** Navigation, mini-player and the player controls are in the bottom half. Secondary actions open a bottom sheet (reachable), never a popup at the top.
4. **Gestures always have a visible or TalkBack equivalent**: long-press and swipe-to-remove are mirrored by menu entries and accessibility actions; back works from either screen edge.
5. **Touch targets >= 48 dp**, small glyphs allowed inside them.

| Screen | Audit result | Change |
|---|---|---|
| Artist / album | Play, Shuffle, Queue, Download clustered left | Full-width action bar: Play (widest), Shuffle, one overflow (queue, save offline) in a bottom sheet; centred hero; two-pane hero at >= 600 dp |
| Queue | Like, overflow and drag handle on every row | Row shows only the drag handle; menu by long-press / TalkBack; swipe to remove |
| Now Playing | Queue icon top-right (far reach) | Queue is a full-width bottom button; top bar keeps only minimise and overflow; swipe down dismisses |
| Home | Search icon at the top duplicated the Search tab | Removed; Search tab is in the bottom bar |
| Search / Library | Filters at top | Unchanged: horizontally scrollable chips are reachable by thumb swipe and do not need precision taps |
| Menus | Popups | Modal bottom sheets everywhere |
