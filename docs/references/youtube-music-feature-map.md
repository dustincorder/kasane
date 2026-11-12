# YouTube Music feature map

## Reference availability

APK supplied: `com.google.android.apps.youtube.music` version `9.45.51` (`94551230`), base split extracted from the supplied APKMirror `.apkm` archive. Package/version were verified with Android build tools. The APK was not installed or modified. Static inspection used the manifest, resource names and decompiled class/resource metadata; no credentials, cookies, or private account data were sought.

Static component names include `MusicActivity`, `MusicPickerActivity`, `AudioPreviewPlayerActivity`, `SettingsCompatActivity`, `MusicPlayerView`, `PlayerPanel`, `SearchToolbarView`, `queue_page`, `account_switcher_fragment`, `PlaybackSettingsFragment`, `RecommendationsSettingsFragment`, and `OfflineSettingsFragmentCompat`. These names establish component/resource presence only; they do not verify which components are reachable in the normal user flow.

The phone's currently active media session belongs to a different YouTube Music client, so it was not used to navigate the supplied APK. As a result, the map below distinguishes static evidence from screens/actions that remain **uninspected**. Resource and class names demonstrate that a feature exists in the package; they do not establish its exact visible layout or interaction behavior. This is not a feature parity claim.

## Original APK — static feature map

| Area / path | Purpose and user actions indicated by static APK inspection | Kasane analogue | Missing evidence / priority |
|---|---|---|---|
| Home → music shelves | Home/YouTube Music activities and shelf-related resource families exist. Exact shelf order, recommendation labels, refresh behavior, and navigation were not inspected. | Home shelves and Listen Again | High: perform UI inspection from an idle session; do not infer the exact recommendation system from resource names. |
| Search → suggestions/results | Search toolbar/input, suggestions, result, no-results, voice-search, and clear-search resources exist. | Typed catalog Search | High: inspect history, suggestion selection, category filters, voice affordance and result menus. |
| Library → songs/albums/artists/playlists | Library/music, saved, album, playlist, history and downloads resource names exist. | Unified local + account Library | High: inspect filter/sort behavior, collection actions and signed-out states. |
| Now Playing → transport/actions | Player-page, artist-link, save-to-library and playback accessibility strings/resources exist. | Shared Media3 Now Playing | High: inspect player action arrangement, lyrics/description availability, and track context menu. |
| Queue → current/up-next | `queue_page`, `queue_page_header`, playlist-panel and queue action resources exist. | Shared Media3 queue | High: exact reorder/remove/add-next gestures remain uninspected. |
| Shuffle / repeat / autoplay | Accessibility strings and shuffle/repeat/autoplay resources exist, including off/on/one states. | Media3 shuffle/repeat; radio handoff | High: inspect mode cycling and autoplay controls. |
| Radio | Radio-named resource families and provider-related strings are present. | InnerTube watch-next radio extraction | High: radio creation flow, source context and continuation UX uninspected. |
| Artist / album / playlist | Artist link, album, playlist, editable playlist, add-to-playlist dialog, playlist metadata/settings resources exist. | Catalog detail pages; account collection provider | Medium: inspect actions, edit permissions, pagination and empty states. |
| Profile / account switcher | Account header, selected account/channel, switcher loading/error/add-account resources exist. | Profile and YTM web-session sign-in/channel selection | Medium: exact channel-switch flow and account management not UI-inspected. |
| Settings / offline | Offline storage, auto-download, network preference, notification, settings and downloaded-song resources exist. | Limited Kasane settings; offline media intentionally absent | Medium: inspect playback quality, network, privacy/history and Premium gates. No offline download behavior was tested. |
| Dialogs / empty / errors | Search no-results, account-switcher errors/loading, playlist edit/add dialogs, offline education resources exist. | Kasane has native loading/error/empty states in selected screens | Medium: catalog exact dialogs and state copy remain uninspected. |

### Static evidence versus UI inspection

- **Confirmed statically:** package/version, manifest component/resource names, account-switcher and playlist-editor resource families, queue/player resource families, search suggestion and no-results resource families, offline/download and settings resource families.
- **Confirmed in real UI:** none of this supplied original APK was installed or navigated in this session.
- **Not inspected:** Home/search/library/player screens, nested menus, gestures, queue editing, artist/album/playlist details, Radio, profile switching, settings values, empty/error copy and Premium/region/account-gated states.
- **Inferences:** feature purpose in the table is inferred from identifiers such as `playlist_edit_metadata` or `autoplay_toggle`, not from observed behavior.

Static inspection found declared media playback and offline-related service families and permissions. This is package structure evidence only; it does not verify that every feature is enabled for this account, region, or subscription.

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
