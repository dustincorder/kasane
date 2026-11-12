# YouTube Music feature map

## Reference availability

The task's `YOUTUBE_MUSIC_APK_PATH` was left empty. No original YouTube Music APK was supplied, so its menus and submenus have **not** been inspected. The currently open device app is not treated as the original APK reference, and no claims below describe uninspected screens.

This map records Kasane's current native implementation and the inspection work still needed once a reference APK is available. It is not a feature parity claim.

## Kasane implementation map

| Area | Kasane entry point | Current implementation | Missing / next priority |
|---|---|---|---|
| Home | `android/app/src/main/java/cord/kasane/music/ui/KasaneApp.kt` | YouTube Music home shelves, Fast Picks and local Listen Again | Verify exact source behavior against supplied APK; improve cache latency and section refresh behavior |
| Search | `KasaneApp.kt`, `CatalogScreens.kt` | Typed Songs/Albums/Artists/Playlists results and catalog navigation | Search history/suggestions and context menus need evidence and implementation |
| Library | `AccountLibraryScreen.kt` | Local Favorites/History and authenticated YTM collections are represented in one library | Local audio, local playlists, offline content and complete sorting/actions are not implemented |
| Artist / album / playlist | `CatalogScreens.kt` | Typed detail pages and playback handoff to the shared Media3 player | Confirm available metadata/actions; pagination and account edits need further coverage |
| Now Playing / queue | `components/NowPlayingSheet.kt`, `playback/` | Shared Media3 controls, queue access, seek and track artwork | Reliable full-track guest streaming is currently blocked by the observed upstream GVS 403; lyrics and richer track actions are not implemented |
| Profile / settings | `ProfileScreen.kt`, `YtMusicLoginDialog.kt` | Guest/signed-in profile and Web-session login flow | Channel/account switching and settings parity need reference inspection |
| Radio | `provider/OnlineCatalog.kt` | YouTube watch-next radio extraction exists in provider code | End-to-end radio UX and queue continuation are not yet verified |
| Downloads | — | Not implemented | Intentionally out of scope; no offline-download claims |

## Original APK inspection checklist

When an authorized local APK path is supplied, inspect and document Home, Search, Library, Now Playing/queue, Radio, Profile/Settings, artist/album/playlist detail pages, menus, dialogs, loading/empty/error states and any Premium/region/account gates. Record only behavior observed through UI/resource inspection; do not extract account data or copy proprietary resources.
