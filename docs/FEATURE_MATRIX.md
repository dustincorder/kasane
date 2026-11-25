# Матрица функциональности

Аудит 2026-11-23, ветка `exp/compose-desktop`. Обновляй строку при каждом изменении функции.

**Статусы.** `IMPLEMENTED` — код подключён к приложению и его логика покрыта автотестом, прошедшим в
этом аудите. `PARTIAL` — работает подмножество. `PROTOTYPE` — подключено, но в экспериментальном клиенте
или без тестов. `UNVERIFIED` — подключено, но результат зависит от живого YouTube/устройства, а
подтверждения в этом аудите нет. `BROKEN` — подтверждённый дефект. `MISSING` — кода нет.

**Чего этот аудит не проверял вообще:** ни одно приложение не запускалось, реальный звук, реальные
ответы YouTube, вход в аккаунт, работа на телефоне, Connect между реальными устройствами. Все
утверждения о живой работе в старых документах остаются непроверенными.

Сокращения путей: `A/` = `android/app/src/main/java/cord/kasane/music/`, `S/` = `shared/src/`,
`U/` = `shared-ui/src/commonMain/kotlin/cord/kasane/ui/`, `C/` = `desktop-compose/src/main/kotlin/cord/kasane/desktop/`,
`T/` = `src-tauri/src/`, `R/` = `crates/`.

## Каталог и поиск

| Функция | Android | Tauri | Compose desktop | Различия и проблемы | Проверка | Перенос |
|---|---|---|---|---|---|---|
| Home (полки, чипы настроений) | IMPLEMENTED `A/ui/HomeScreen.kt`, `HomeComposer.kt` | MISSING | IMPLEMENTED `C/ui/HomeComposer.kt` + `Screens.kt` | ту же семантику: Quick picks из лид-полки + topPlayed + лайки, Recently played, Radio seeds; локальные блоки после второй удалённой полки; mood chip скрывает локальное; дедуп треков между секциями; гостевая история не смешивается с аккаунтом | `C:HomeComposerTest`; `--render` (guest feed) | `HomeComposer` без Android-зависимостей → `:shared` |
| Поиск | IMPLEMENTED 5 типов, подсказки, история `A/ui/KasaneApp.kt` | PARTIAL только песни `R/kasane-providers/src/ytmusic.rs` | PARTIAL 5 типов + «Load more» по continuation; в Offline Only — поиск по сохранённым; подсказок и истории нет | три набора возможностей | `CatalogJsonParserTest`, `InnerTubeTransportTest`; Rust `search_*` | каталог уже общий; история поиска → `:shared` |
| Страницы альбома/артиста/плейлиста | IMPLEMENTED `A/ui/CatalogScreens.kt` | MISSING | IMPLEMENTED `C/ui/Screens.kt` BrowseScreen | пагинация плейлистов, Find-in-page, Sort (title/artist/album) с честной дозагрузкой всех страниц, bulk Queue all / Save offline / Add all to playlist, Mix/Radio | `RadioCaptureTest`, `PlaylistNavigationTest`; `--render` плейлист-страницы | экраны → `:ui` |
| Кэш каталога (SWR) | IMPLEMENTED `A/data/CatalogCache.kt` | MISSING | MISSING | — | `CatalogCacheTest` | логика без Android-зависимостей → `:shared`; хранилище за интерфейсом уже есть |
| Обложки | IMPLEMENTED Coil | IMPLEMENTED `<img>` | PROTOTYPE OkHttp+Skia | общий `ArtworkSizing` у Kotlin-клиентов | `ArtworkSizingTest`, `ThumbnailParserTest` | сделано |

## Воспроизведение

| Функция | Android | Tauri | Compose desktop | Различия и проблемы | Проверка | Перенос |
|---|---|---|---|---|---|---|
| Движок | Media3 `A/playback/PlaybackService.kt` | rodio `R/kasane-audio/src/desktop.rs` | mpv `C/audio/MpvEngine.kt` за интерфейсом `C/audio/AudioEngine.kt` | платформенное по природе | Rust: 14 тестов; Android: `OfflineCacheIntegrationTest`; mpv — без тестов | остаётся за портом `AudioEngine` |
| Выбор потока | UNVERIFIED `A/provider/PlaybackStream.kt` | UNVERIFIED `ytmusic.rs` `resolve_stream` | IMPLEMENTED `C/playback/StreamSelector.kt` | три разных порядка (см. `ARCHITECTURE.md` §5); KI-7 | юнит-тесты на фикстурах: `HlsPlayerTransportTest`, `VerifiedPlayerResolverTest`, Rust `player_fixtures_*`. Живые пробы: `--play`/`--smoke` играют треки целиком через verified; iOS HLS не отдаёт манифест, гостевой режется ~29 с | одна цепочка `StreamSelector` в `:shared` |
| Верифицированный WEB_REMIX (BotGuard) | UNVERIFIED `S/jvmCommonMain/.../verification/` + `A/provider/verification/` | UNVERIFIED `R/kasane-providers/src/verification.rs`, `T/verifier.rs` | IMPLEMENTED `C/verify/` (WebKitGTK-хелпер, состояние переигрывается при рестарте) | резолвер общий у Kotlin, свой у Rust; ломается при изменениях YouTube | фикстурные тесты; живьём: `--play` до EOF, рестарт хелпера по `KASANE_VERIFIER_IDLE_MS` | Kotlin-часть уже общая |
| iOS HLS | UNVERIFIED (своя копия запроса в `:app`) | UNVERIFIED свой декодер `R/kasane-audio/src/hls.rs` | UNVERIFIED `S/.../IosHlsResolver.kt` | две Kotlin-копии одного запроса; KI-7; живая проба: клиент не отдаёт `hlsManifestUrl` | `HlsSelectionTest`, Rust `hls::tests` (6) | Android → `IosHlsResolver` |
| SABR | UNVERIFIED `A/provider/sabr/`, `A/playback/SabrDataSource.kt` | MISSING | MISSING | по `docs/sabr-playback.md` полное воспроизведение не подтверждено | `SabrSessionTest` на синтетике | после подтверждения |
| Гостевой ANDROID_VR | код есть, не вызывается | MISSING | PROTOTYPE последний вариант | YouTube обрывает поток после первой части (~29 с), для части треков `LOGIN_REQUIRED`; Compose сообщает об этом явно | `GuestPlayerResolverTest`; живая проба подтвердила обрыв | уже в `:shared` |
| Play/pause/seek/next/prev | IMPLEMENTED `A/playback/PlaybackManager.kt` | IMPLEMENTED `R/kasane-audio/src/lib.rs` | IMPLEMENTED `C/playback/DesktopPlayer.kt` | — | `KasanePlaybackAndSearchTest`, `PlaybackFailurePolicyTest`; Rust `PlayerManager` тесты; `DesktopPlayerTest` (21) + живые `--smoke`/`--play` до EOF/`--connect-smoke` | общий контракт `PlayerController` |
| Громкость | системная | IMPLEMENTED | PROTOTYPE, сохраняется в настройках | — | нет | — |
| Shuffle / repeat | IMPLEMENTED (Media3) | PARTIAL: repeat только командой Connect, shuffle не реализован, кнопок в UI нет | IMPLEMENTED свой алгоритм | на Android shuffle — порядок таймлайна, на Compose — перестановка списка | Android: `QueueBehaviorTest`; Compose: `DesktopPlayerTest` | правило в `QueuePolicy` |
| Сообщение о резервном источнике | IMPLEMENTED `PlaybackNotices` | IMPLEMENTED `PlaybackProgress.notice` | IMPLEMENTED `notice` → баннер в `App.kt` | — | Rust `fallback_notice_*`; Compose: `DesktopPlayerTest` | вместе с `StreamSelector` |

## Очередь и радио

| Функция | Android | Tauri | Compose desktop | Различия и проблемы | Проверка | Перенос |
|---|---|---|---|---|---|---|
| Правила очереди (новый контекст, play next, перемещение) | IMPLEMENTED `S/commonMain/.../QueuePolicy.kt` | свои правила в `PlayerManager` | PROTOTYPE тот же `QueuePolicy` | Tauri: выбор трека вставляет его после текущего, а не начинает новый контекст | `QueuePolicyTest`, `QueueBehaviorTest` (shared) | сделано для Kotlin |
| Экран очереди | IMPLEMENTED `U/screens/QueueScreen.kt` в bottom sheet | PARTIAL список и выбор; удаления и перестановки нет | IMPLEMENTED `C/ui/Player.kt` QueueList | свой список (нет reorderable-зависимости): Move up/down/Remove + полное меню трека; семантика удаления текущего выровнена с Android | `DesktopPlayerTest`; UI-тестов очереди нет | секции → `QueuePolicy.sections` в `:shared` |
| Радио / автодозагрузка | IMPLEMENTED `PlaybackService.fillRadio` | MISSING | IMPLEMENTED `DesktopPlayer` + `RadioSource` | две независимые реализации; KI-8 | `RadioMergeTest`, `RadioCaptureTest`, `RadioAndLikesRequestTest`; Compose: `DesktopPlayerTest` (дозагрузка + отказ от устаревшей страницы) | `RadioController` в `:shared` |
| Настройка Autoplay | IMPLEMENTED `AppSettings.autoplayFlow` (legacy prefs `playback` мигрируются разово) | MISSING | MISSING — радио всегда включено | — | `QueuePolicy.shouldPrefetchRadio` в тестах | общая модель настроек |
| «Start radio» из меню | IMPLEMENTED `PlaybackManager.startRadio` — якорное радио `QueuePolicy.anchoredRadio`: играющий трек не рестартует, заменяется только хвост | MISSING | PROTOTYPE | — | `S:QueuePolicyTest` | — |

## Библиотека и аккаунт

| Функция | Android | Tauri | Compose desktop | Различия и проблемы | Проверка | Перенос |
|---|---|---|---|---|---|---|
| Локальное избранное | REMOVED — записей в `favorites` больше нет (таблица оставлена для экспорта); лайки только облачные | IMPLEMENTED SQLite `R/kasane-db` | MISSING — убрано сознательно | единая семантика: локальной системы лайков нет, только YTM-рейтинги | `LikedSongsTest` | — |
| История | IMPLEMENTED дневная группировка, удалённая история YTM (`FEmusic_history` через catalog cache), миксы записываются при `startRadio` и перезапускаются, лимит локальной ленты 300, запись по порогу `S:PlayThreshold` (30 с или половина короткого трека) + watchtime-пинг в аккаунт (`S:WatchReport`, только при входе) `A/ui/HistoryScreen.kt`, `A/provider/RemoteHistory.kt`, `A/playback/PlaybackService.maybeRecordHistory` | PARTIAL: длительность всегда 0, запись при выборе трека | IMPLEMENTED `C/library/LocalLibrary.kt` + `HistoryGrouping.kt` (делегирует shared `jvmCommonMain groupHistoryByDay`) | per-account persisted (`accounts` map в JSON, ключ = channelId/name); гость — только память, на диск не пишется; удалённая дневная лента `FEmusic_history` при входе; миксы записываются и перезапускаются; лимит 300 | `S:SessionAndHistoryPolicyTest`, `C:HistoryGroupingTest`, `C:LocalLibraryTest`, `DesktopPlayerTest` | гостевой opt-in на Android; на desktop гость никогда не пишется |
| Вход в аккаунт | UNVERIFIED WebView + Keystore `A/ui/YtMusicLoginDialog.kt`, `A/auth/`; фаза входа `S:SignInPhase` проброшена в UI (STARTING/WINDOW_OPEN/VALIDATING), мёртвая блокировка кнопки снята; `SessionMode` гейтит восстановление сессии | UNVERIFIED окно Tauri + keyring `T/account.rs` | UNVERIFIED `C/auth/DesktopAccount.kt`, `C/login/`, `C/ui/Profile.kt`: вход/смена аккаунта из меню профиля | исправлен разбор jar под soup3 (`get_cookies_finish` → GList/SoupCookie, не строка); end-to-end вход в реальный аккаунт ещё раз проверить вручную | `DesktopAccountTest`, `SecretServiceStoreTest`, JNA-проба cookie jar (6 cookie с music.youtube.com), рендер гостевого профиля `--render` | `SessionStore` уже порт |
| Библиотека аккаунта | UNVERIFIED плейлисты, альбомы, подписки + Liked songs (VLLM) `A/ui/AccountLibraryScreen.kt` | UNVERIFIED лайки, плейлисты, треки плейлиста (чтение) `ytmusic.rs` | IMPLEMENTED `C/library/LikedSongs.kt` + `AccountCollectionList` | Liked songs = реальный плейлист LM с пагинацией; вкладки Playlists/Albums/Artists при входе; пользователь подтвердил загрузку | `C:LikedSongsTest`, `AccountLibraryRequestTest` | клиент уже в `:shared` |
| Облачные лайк/дизлайк | IMPLEMENTED `S/commonMain/.../provider/CloudLikes.kt` (из :app в shared), `LocalCloudLikes` везде; Liked songs = плейлист `VLLM`; офлайн/гость — кнопка disabled | MISSING | IMPLEMENTED `C/library/DesktopLikes.kt` — тонкий адаптер над shared `CloudLikes` | read=`catalog.likeState`, write=`setLikedFor`/`setDislikedFor`; session/gate/hints в адаптере, memo/permits/rollback — shared; guest/offline — действия недоступны; локальных избранных нет | `C:LikedSongsTest` (toggle→удаление из списка, like→prepend); запись в аккаунт автотестами не проверяется | — |
| Добавить в плейлист | UNVERIFIED `S/jvmCommonMain/.../provider/PlaylistPicker.kt` (перенесён из :app); массовое «Add all to playlist…» на экране коллекции `A/ui/CatalogScreens.kt` | MISSING | IMPLEMENTED `C/library/DesktopPlaylistPicker.kt` + `C/ui/PlaylistPicker.kt` | та же машина состояний: потерянный ответ записи → чтение членства, неподтверждённое — ошибка; массовое добавление с экрана коллекции | `C:PlaylistPickerTest` | сделано — `PlaylistPicker` в `:shared`; `DesktopPlaylistPicker` — тонкий адаптер (session-гейт, трансляция `SessionExpiredException`) |
| Действия коллекции (play next, очередь, find, sort, save all, mix) | IMPLEMENTED `A/ui/CatalogScreens.kt` + `PlaybackManager.playAllNext/addAllToQueue` | MISSING | IMPLEMENTED `C/ui/Screens.kt` BrowseScreen | Queue all, Save offline, Add all to playlist, Find, Sort, Radio/Mix; find/sort догружают всю пагинацию; bulk со статусом | `--render` плейлиста (кнопки видны), `C:PlaylistPickerTest` | bulk-очередь → `QueuePolicy` |
| Создать/переименовать/удалить плейлист | MISSING | MISSING | MISSING | — | — | — |
| Экспорт локальных данных | IMPLEMENTED `A/data/LocalDataExport.kt` | MISSING | MISSING | локальные плейлисты всегда пусты | `LocalDataExporterTest` | → `:shared` |
| Импорт (InnerTune, M3U и др.) | PROTOTYPE: парсеры без UI `S/commonMain/.../library/` | MISSING | MISSING | код не вызывается приложениями | `LibraryImportTest`, `InnerTuneBackupAdapterTest` | уже общий |

## Офлайн и локальные файлы

| Функция | Android | Tauri | Compose desktop | Различия и проблемы | Проверка | Перенос |
|---|---|---|---|---|---|---|
| Кэш прослушанного | IMPLEMENTED `A/offline/AudioStores.kt` | MISSING | IMPLEMENTED `C/offline/DesktopOffline.kt` | tee-кэш через `StreamProxy.streamSink` → `onStreamBytes`; LRU-лимит `cacheLimitMb`; метаданные трека через `played()`+`pendingTracks`; только progressive (HLS/SABR не кэшируются — `resolveForSave` их не отдаёт) | `C:DesktopOfflineTest` (локальный HTTP с Range), `--render` Downloads (трек закэшировался) | политика → `:shared`, хранение платформенное |
| Сохранение офлайн | IMPLEMENTED, ждёт приёмки на устройстве `A/offline/OfflineManager.kt` | MISSING | IMPLEMENTED `C/offline/DesktopOffline.kt` + `C/ui/DownloadsScreen.kt` | те же правила: очередь по одному, ranges-резюм (×6, ≥512 КБ), 3 попытки, транзиент → повтор после рестарта, `verifySaved` при старте, artwork в app-private файле; UI: Saved/Downloading/Needs attention/Cached, прогресс, cancel/retry/remove | `C:DesktopOfflineTest` (сохранение→saved→байты совпали, рестарт→saved, dedup, fail-reason, remove, badges) | политика → `:shared` |
| Режим Offline Only | IMPLEMENTED `OfflineGateInterceptor` | MISSING | IMPLEMENTED `OfflineModeException` + interceptor в `DesktopApp` | тумблер в Downloads & storage; режет весь OkHttp (каталог, обложки, потоки); поиск идёт по сохранённым; воспроизведение — `OfflineStreamSource` до сети | `C:DesktopOfflineTest`; интерцептор покрыт косвенно | interceptor → `jvmCommonMain` |
| Локальные аудиофайлы | MISSING (есть только поле `isLocal`) | PARTIAL: открыть один файл диалогом `T/lib.rs` `open_audio_file` | MISSING | библиотеки локальных файлов нет нигде | нет | сканер — порт; модель — общая |

## Kasane Connect

| Функция | Android | Tauri | Compose desktop | Различия и проблемы | Проверка | Перенос |
|---|---|---|---|---|---|---|
| Протокол, TLS, сопряжение | IMPLEMENTED `S/jvmCommonMain/.../connect/` | IMPLEMENTED `R/kasane-connect` | PROTOTYPE то же Kotlin-ядро | две реализации одного протокола | Kotlin: `ConnectLoopbackTest`, `ConnectVectorsTest`; Rust: `loopback` (9), `vectors` (4). Kotlin↔Rust `ConnectInteropTest` пропущен (нужен `KASANE_RUST_CLI`) | при отказе от Tauri остаётся одна |
| Обнаружение | NSD `A/connect/NsdDiscovery.kt` | mdns-sd | JmDNS + свой ответчик | платформенное | нет автотестов | порт уже есть |
| Удалённое управление, передача воспроизведения | UNVERIFIED на устройствах | UNVERIFIED | UNVERIFIED | Tauri отвечает ошибкой на команду shuffle | loopback-тесты; реальные устройства не проверялись | — |
| Экран устройств | IMPLEMENTED `U/screens/ConnectDevicesScreen.kt` | IMPLEMENTED `src/lib/components/DevicesView.svelte` | PROTOTYPE тот же общий экран | — | нет UI-тестов | сделано для Kotlin |

## Система и оформление

| Функция | Android | Tauri | Compose desktop | Различия и проблемы | Проверка | Перенос |
|---|---|---|---|---|---|---|
| Системные медиа-контролы | IMPLEMENTED MediaSession | MISSING (нет MPRIS) | MISSING (нет MPRIS) | — | нет | порт `SystemMedia` |
| Фоновое воспроизведение | IMPLEMENTED foreground service | пока открыто окно | пока открыто окно | трея нет | нет | — |
| Тема, акценты | IMPLEMENTED Material You `U/theme/Theme.kt` | фиксированная палитра Rose `tailwind.config.js` | PROTOTYPE seed-цвета, общий `AppearanceSection` | Tauri выглядит как другой продукт | `ScreensSmokeTest` (Robolectric) | сделано для Kotlin |
| Масштаб интерфейса | системный | системный | PROTOTYPE `C/SystemScale.kt` | — | `ScaleTest` | — |
| Desktop профиль и настройки | профиль через Home top bar, настройки `A/ui/ProfileScreen.kt` | заморожен | `C/ui/Profile.kt`, `SettingsScreen.kt`: профиль в rail, меню истории/Downloads/Connect/настроек, категорийные настройки с узкой компоновкой | Downloads & storage реализован (offline only, автокэш, лимит, очистка, enrich metadata); Recommendations пока нет | `:desktop:test`, `--render` (guest, settings, narrow), GUI навигация | визуальная семантика совпадает с Android, настройки desktop-only для масштаба |
| Горячие клавиши | — | только Escape/Enter | PROTOTYPE пробел, стрелки, Ctrl +/−/0, F | медиа-клавиш нет | нет | — |
| Меню трека (play next, queue, radio, лайк, плейлист, офлайн, альбом, артист, share) | IMPLEMENTED `A/ui/components/TrackContextMenu.kt` | PARTIAL: в очередь, избранное | IMPLEMENTED `C/ui/Components.kt` `TrackDropdownMenu` | общий `MenuEntry(label, enabled, icon, group, action)`; ПКМ по треку в списках/очереди/Now Playing + кнопка «⋯»; офлайн-группа (Save/Remove/Cancel/Retry) по `OfflineBadge` | `TrackMenuPolicyTest`; `--render` | политика меню → `:shared`, строка → `:ui` |
| Тексты песен | IMPLEMENTED shared-подсистема `S/commonMain/.../lyrics/` (AMLL TTML DB + LRCLIB + YTM, LRC/enhanced-LRC/TTML парсеры, резолвер word>line>plain, ручной выбор источника, смещение ±250мс, Room-офлайн v9, префетч при сохранении трека); синхронный шит `A/ui/components/LyricsSheet.kt` (скролл к активной строке, тап=seek, димминг, инструментальные паузы) | — | IMPLEMENTED `C/lyrics/DesktopLyrics.kt` — тот же `LyricsResolver` (AMLL→LRCLIB→YTM), `FileLyricsStore` (JSON), `DesktopYtmLyricsProvider` (next→browseId→browse), `C/ui/LyricsPanel.kt` (active line/word, tap-to-seek, ±250мс, Sources) | пословная синхронизация только если у провайдера есть word-таймкоды; AMLL матчинг = точный title + общий токен артиста; Android различает `LyricsResolution.Absent` (нет текста) и `Unavailable` (ошибка загрузки); визуал доводит Gemini; на устройстве не проверено | `S:LyricsParserSyncTest`, `AmlTtmlProviderTest`, `C:DesktopLyricsTest` (store+парсер YTM); `--render` lyrics-panel | YTM-парсер продублирован (`A/provider/TrackLyrics.kt` ↔ `C/lyrics`) — кандидат в `:shared` |
| Метаданные релиза | IMPLEMENTED MusicBrainz + Cover Art Archive `S/commonMain/.../metadata/` (`MusicBrainzProvider`, `MetadataResolver`), Room `track_metadata` (v9), фоновое обогащение сохранённых треков, настройка «Save release details» | — | IMPLEMENTED `C/metadata/DesktopMetadata.kt` — тот же `MetadataResolver`, `FileMetadataStore` (JSON), OkHttp-транспорт 1 req/s + UA; обогащение после сохранения (`onSaved` в `DesktopOffline`), официальная обложка `saveArtworkFrom` репоинтит thumbnailUri; настройка `enrichMetadata` | только сохранённые треки обогащаются; без подмены обложки случайным совпадением (CAA front only); UI-показ даты/жанра — за Gemini | `S:MusicBrainzProviderTest`, `C:FileMetadataStoreTest` | — |
| Онбординг | IMPLEMENTED двухшаговый: вход/гость → настройки (автокэш+лимит, Wi-Fi only, offline only, метаданные, история) `A/ui/OnboardingScreen.kt`; `SessionMode` (GUEST не воскрешает сессию) + `SessionPolicy.showsOnboarding` (нет онбординга при живой сессии) | — | IMPLEMENTED `C/ui/Onboarding.kt` — та же двухшаговая схема (sign-in/guest → автокэш+лимит, метаданные), гейт по `prefs.onboardingDone` | на desktop нет Wi-Fi only и guest-history тумблера (нет мобильного трафика, гостевая история всегда в памяти); SigningIn прогресс повторяет Android | `--render` 0-onboarding, `A:SessionModeStoreTest` | — |
| История: гость vs аккаунт | IMPLEMENTED `HistoryEntity.owner` (v9), гость — opt-in (`guestSaveHistory`, выкл. по умолчанию), ленты не смешиваются, тумблер в Data & privacy | — | IMPLEMENTED `LocalLibrary.accounts` | гость — всегда memory-only (не opt-in), на диск не пишется; аккаунтные ленты разделены ключом channelId/name | `C:LocalLibraryTest` (гость↔аккаунт↔другой аккаунт) | — |
| Поделиться | IMPLEMENTED | MISSING | IMPLEMENTED `actions.share` — watch-URL в буфер; `loadTrackLinks` для меню | — | `TrackLinksTest` | — |
| Сборка дистрибутива | debug APK собран в этом аудите | не собиралось | не собиралось | release APK требует ключей в `~/.glazegram` | `:app:assembleDebug` | — |

## Отсутствует на всех клиентах

Таймер сна, эквалайзер, Last.fm, SponsorBlock, локальные плейлисты, рекомендации на
основе прослушиваний, gapless/crossfade (есть только неподключённая математика в `S/commonMain/.../mix/`),
AI-провайдеры (есть только пустой трейт в `R/kasane-core/src/traits.rs`).
