# Desktop parity — contracts for the shared-logic owner

Что Compose Desktop продублировал под Android и что логично перенести в `:shared`/`jvmCommonMain`,
чтобы правила жили в одном месте. Desktop-реализации рабочие — это запросы на слияние, не блокеры.

## Перенос в shared (платформенно-независимое, без изменения семантики)

| Desktop | Android-оригинал | Что общее |
|---|---|---|
| `C/library/DesktopPlaylistPicker.kt` | `A/provider/PlaylistPicker.kt` | ✅ Сделано: shared `PlaylistPicker` в `jvmCommonMain` — desktop-файл теперь адаптер (session-гейт, rebuild при смене аккаунта, трансляция `CatalogFailure(SESSION_EXPIRED)` → `SessionExpiredException`). |
| `C/library/HistoryGrouping.kt` | `A/ui/HistoryGrouping.kt` | ✅ Сделано: generic `groupHistoryByDay` в `S/jvmCommonMain/.../library/HistoryGrouping.kt`, desktop-файл — тонкий адаптер под `HistoryEntry`. |
| `C/library/DesktopLikes.kt` | `A/provider/CloudLikes.kt` | ✅ Сделано: `S/commonMain/.../provider/CloudLikes.kt` — desktop `DesktopLikes` теперь тонкий адаптер (session-binding, track hints, gate, rebuild-on-signout); memo/permits/rollback — shared. |
| `C/library/LikedSongs.kt` | экран Liked на Android | Пагинация LM + дедуп + синхронизация с рейтингами (unlike→remove, like→prepend). Логика UI-независима. |
| `C/ui/HomeComposer.kt` | `A/ui/HomeComposer.kt` + `HomeFeedPolicy.kt` | Правила сборки Home (lead shelf → Quick picks, local-блоки после 2-й удалённой полки, дедуп, mood→remote-only). Desktop-версия написана под `CatalogShelf`/`Track` — сигнатуры уже shared-совместимые. |
| `C/lyrics/DesktopLyrics.kt` (`DesktopYtmLyricsProvider` + `YtmLyricsParser`) | `A/provider/TrackLyrics.kt` + `YtmLyricsProvider.kt` | Парсер `next`→LYRICS browseId→`browse` и провайдер идентичны по логике; различие — `OnlineCatalog.request` vs `SharedOnlineCatalog.request`. Просьба: общий `TrackLyricsParser` + `YtmLyricsProvider` в `S/lyrics/` (конструктор — по `request(endpoint, payload, account)`), тогда desktop-копия удаляется. |

## Offline: общая политика, платформенное хранение

`C/offline/DesktopOffline.kt` копирует правила `A/offline/OfflineManager.kt`: один воркер, ranges-резюм
(PARALLEL_RANGES=6, MIN_RANGE_BYTES=512K, MAX_ATTEMPTS=3), transient-переочередь после рестарта,
ожидание сети вместо фейла, `OfflineBadge` (SAVED/DOWNLOADING/FAILED/QUEUED), отдельные saved/temp
хранилища с LRU-лимитом.

Просьба к Android-агенту: вынести в `:shared` чистую политику — `mergeRanges`/`chunks`, retry-график,
mapping `state→badge`, тексты причин блокировки. Хранилища остаются платформенными (Room+SimpleCache
vs файлы+JSON-индекс).

## UI-контракты (для Gemini / shared-ui)

- `MenuEntry(label, enabled, icon, group, action)` — Gemini уже расширил в `shared-ui`. Desktop
  очередь использует `QueuePolicy.sections` + собственный список; если Android-агент добавит
  `extraMenu`/меню-трек в общий `QueueContent`, desktop перейдёт на него.
- `TrackListItem` из `shared-ui` на desktop по-прежнему не используется (свой `TrackRow` с ПКМ и
  «⋯»-кнопкой) — визуальный слой за Gemini, семантика меню выровнена с Android.

## Что сознательно отличается от Android

- Локального избранного нет — лайки только облачные (`DesktopLikes`), как требовал владелец.
- Гостевая история — memory-only всегда (на Android — opt-in `guestSaveHistory`; desktop пишет
  только подписанные аккаунты).
- `Offline Only` режет весь OkHttp через `OfflineModeException`; на Android — отдельный
  `OfflineGateInterceptor`. Общий интерцептор логично поднять в `jvmCommonMain`.
