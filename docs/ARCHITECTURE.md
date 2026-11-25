# Архитектура Kasane (фактическая)

Состояние на 2026-11-23, ветка `exp/compose-desktop` (46 коммитов впереди `main`). Описан код, а не
планы. Целевая архитектура и путь к ней — в конце, отдельным разделом.

## 1. Что есть в репозитории

Три клиента на двух несвязанных технологических стеках.

| Клиент | Путь | Стек | Статус |
|---|---|---|---|
| Android | `android/app` (Gradle `:app`) | Kotlin 2.0.21, Compose, Media3 1.5.1, Room 2.6.1, OkHttp | основной, самый полный |
| Desktop Tauri | `src/`, `src-tauri/`, `crates/*` | Rust, Tauri 2, Svelte 5, rodio, rusqlite | рабочий, узкий по функциям |
| Desktop Compose | `desktop-compose/` (Gradle `:desktop`) | Kotlin/JVM, Compose Multiplatform 1.7.3, mpv, JNA→WebKitGTK | прототип, делит код с Android |

Общие модули Kotlin (Gradle-проект собирается из `android/`):

| Модуль | Путь | Цели | Содержимое |
|---|---|---|---|
| `:shared` | `shared/` | android, jvm | `commonMain`: модели, InnerTube-клиент и парсеры, `QueuePolicy`, контракты аккаунта, импорт/экспорт, mix. `jvmCommonMain`: OkHttp-транспорт, резолверы потоков, `AudioRange`, ядро Connect |
| `:ui` | `shared-ui/` | android, jvm | тема, `TrackArtwork`, очередь (`QueueContent`), Appearance, Connect-экран и диалог сопряжения |
| `:poc-webview` | `poc/webview-verify/` | jvm | изолированный эксперимент, не входит ни в одно приложение |

Rust workspace (`Cargo.toml`): `kasane-core` (модели, трейты), `kasane-providers` (InnerTube, аккаунт,
верификация), `kasane-audio` (очередь, rodio, HLS-декодер), `kasane-db` (SQLite), `kasane-connect`
(протокол + CLI), `src-tauri` (приложение). Rust-код не используется ни Android, ни Compose desktop.

Общий для всех трёх клиентов артефакт один: `verifier/entry.js` → `verifier/dist/kasane-verifier.js`
(BotGuard/PoToken + дешифровка player JS). Его встраивают Android (assets), Tauri (`public/verifier.html`)
и Compose desktop (resources).

Прочее: `desktopApp/` — только остатки сборки удалённого предшественника (в git не отслеживается);
`dist/`, `target/`, `node_modules/` — артефакты; `scripts/` — безопасная установка APK, e2e-пробы,
фикстуры; `.agents/` — описания субагентов и навыков.

## 2. Android (`android/app/src/main/java/cord/kasane/music`)

Ручной DI в `KasaneApplication`: Room, один `OkHttpClient` с `OfflineGateInterceptor`,
`YouTubeMusicProvider`, `AccountRepository`, `OfflineManager`, `PlaybackManager`, Connect.
Один Activity, один корневой composable `ui/KasaneApp.kt` (926 строк: состояние вкладок, стек деталей,
загрузка Home, диалоги). ViewModel и Navigation Compose не используются, хотя зависимости подключены.

- **Воспроизведение.** `PlaybackService` (MediaSessionService + ExoPlayer) владеет очередью (таймлайн
  Media3) и радио (`configureRadio`/`fillRadio`). `PlaybackManager` — проекция `MediaController` в
  `StateFlow`, опрос каждые 400 мс. Элемент очереди — `kasane://track/<id>`, подписанный URL в очередь
  не попадает. `TransportMediaSource` при подготовке выбирает: локальная копия → HLS → SABR →
  progressive.
- **Резолв потока.** `provider/PlaybackStream.kt` `HlsPlayerResolver`: WEB_REMIX с сессией аккаунта
  (HLS / SABR / progressive с пробой CDN) → верифицированный WEB_REMIX (`VerifiedPlayerResolver` из
  `:shared` + `WebViewVerificationRuntime`) → анонимный iOS HLS. Цепочка живёт в `:app`.
- **Каталог.** `OnlineCatalog` — тонкий фасад над `SharedOnlineCatalog`; `CatalogCache` +
  `CachedCatalogRepository` — SWR-кэш в Room.
- **Аккаунт.** WebView-вход (`ui/YtMusicLoginDialog.kt`), `WebSession`, `KeystoreSessionStore`
  (AES-GCM), `AccountRepository`. `CloudLikes` (оптимистичные лайки/дизлайки с откатом),
  `PlaylistPicker`.
- **Хранение.** Room v7, 6 миграций, `exportSchema = false`: `tracks`, `favorites`, `history`,
  `audio_files`, 4 таблицы `catalog_cache_*`. Настройки — SharedPreferences в трёх файлах:
  `kasane_settings` (`AppSettings`), `playback` (ключ `autoplay`, читается в 4 местах напрямую),
  история поиска.
- **Офлайн.** `offline/`: два `SimpleCache` (временный LRU и сохранённый), `OfflineManager`
  (очередь загрузок, возобновление), `OfflinePolicy`.
- **Connect.** `connect/`: NSD-обнаружение, `AndroidPlayerAdapter`, `RemoteController`; ядро — в
  `:shared`.

## 3. Desktop Tauri

`src-tauri/src/lib.rs` регистрирует 37 IPC-команд: воспроизведение и очередь (15), Connect (14),
аккаунт и хранилище (7), ответ верификатора (1). Фронтенд (`src/lib/stores/*.svelte.ts`) опрашивает
`get_playback_state` + `get_queue` каждые 500 мс; этот же опрос продвигает очередь
(`advance_if_finished`).

- `kasane-audio::PlayerManager` — очередь в памяти; `DesktopAudioBackend` — отдельный поток rodio.
  Progressive-поток скачивается в память целиком диапазонами по 1 МиБ, HLS декодируется собственным
  `hls.rs`.
- `kasane-providers`: поиск только песен, резолв «верифицированный WEB_REMIX → iOS HLS», аккаунт
  (личность, лайки, плейлисты — только чтение). Верификатор — скрытое окно WebKitGTK
  (`src-tauri/src/verifier.rs`).
- Сессия — Secret Service через `keyring`, при недоступности только в памяти.
- `kasane-db`: `favorites`, `history`, `track_features` (последняя таблица не используется).
- CSP в `tauri.conf.json` отключён (`"csp": null`); IPC-доступ ограничен окном `main`.

## 4. Desktop Compose (прототип)

`DesktopApp` — аналог `KasaneApplication`. Каталог — `SharedOnlineCatalog` без сессии (только гость).
`DesktopPlayer` держит очередь в `StateFlow`, применяет `QueuePolicy`, сам реализует радио.
Звук — отдельный процесс `mpv` по JSON IPC (`MpvEngine`); progressive-потоки идут через локальный
прокси `StreamProxy` (127.0.0.1, токен на поток, ограниченные range-запросы). Резолв:
верифицированный WEB_REMIX → iOS HLS → гостевой ANDROID_VR. Верификация — системный WebKitGTK через
JNA во вспомогательном процессе (`--verifier`, `verify/`). Данные — JSON-файлы в
`~/.local/share/cord.kasane.compose/` (`library.json`, `settings.json`, `connect/`). Connect — то же
Kotlin-ядро, обнаружение через JmDNS + собственный mDNS-ответчик. Headless-режимы: `--smoke`,
`--connect-smoke`, `--render`, `--live`, `--play`.

## 5. Что общее, а что продублировано

| Логика | Android | Compose desktop | Tauri |
|---|---|---|---|
| InnerTube-запросы и парсеры каталога | `:shared` | `:shared` | свой Rust (`ytmusic.rs`, `account.rs`) |
| Правила очереди | `:shared` `QueuePolicy` | `:shared` `QueuePolicy` | свой Rust (`PlayerManager`) |
| Владелец очереди и состояние плеера | `PlaybackService`/`PlaybackManager` | `DesktopPlayer` | `PlayerManager` |
| Радио и автодозагрузка | `PlaybackService` | `DesktopPlayer` (второй вариант) | нет |
| Цепочка выбора потока | `HlsPlayerResolver` в `:app` | внутри `DesktopPlayer.loadCurrent` | `ytmusic.rs` |
| Запрос iOS HLS | свой в `:app` | `:shared` `IosHlsResolver` | свой Rust |
| Верифицированный резолв | `:shared` | `:shared` | свой Rust (`verification.rs`) |
| Среда верификации | WebView | WebKitGTK/JNA | окно Tauri |
| Ядро Connect | `:shared` | `:shared` | свой Rust (`kasane-connect`) |
| Избранное и история | Room | JSON | SQLite |
| Сессия аккаунта | Keystore | нет | keyring |
| Облачные лайки, кэш каталога, выбор плейлиста, Home-композиция, офлайн-политика | `:app` (без Android-зависимостей) | нет | нет |

Три разных порядка выбора потока означают, что один и тот же трек может играть на одном клиенте и
не играть на другом.

## 6. Неиспользуемый и экспериментальный код

Проверено поиском вызовов, а не по названиям.

- Android: `playback/YouTubeDataSource.kt` (нет ни одного использования), гостевой путь
  `YouTubeMusicProvider.playerResolver`/`resolveStreamUrl`/`getHomeRecommendations` (не вызываются),
  `library/InnerTuneBackupAdapter.kt` (только тест), зависимости `navigation-compose` и
  `lifecycle-viewmodel-compose`.
- `:shared`: `mix/*` (только тесты), `library/ImportPlanner`, `TrackMatcher`, `PlaylistFormats`,
  `KasaneExport` (только тесты и неиспользуемый адаптер).
- `:ui`: `components/TrackListItem.kt` не используется ни одним приложением — у каждого своя строка трека.
- Rust: трейты `AudioAnalyzer`, `AIProvider`; `NativeDspAnalyzer` возвращает значения, вычисленные из
  хэша id трека, а не из аудио; `MockProvider`; таблица `track_features`.
- `poc/webview-verify`, `crates/kasane-audio/examples/youtube_smoke.rs`, `desktopApp/`.
- SABR на Android (`provider/sabr/`, `SabrDataSource`) подключён, но по `docs/sabr-playback.md`
  реальное воспроизведение не подтверждено.

## 7. Почему клиенты расходятся

1. **Два стека без общего кода.** Всё, что есть в Tauri, написано второй раз на Rust: InnerTube,
   верификация, Connect, очередь, хранилище. Функция, сделанная на Android, на Tauri не появляется
   без повторной реализации.
2. **Стратегия менялась без завершения.** По `git log` и документам: Tauri → решение о KMP и запрет
   нового Rust (`docs/ROADMAP.md` от 16.11) → новые Rust-функции (Connect, вход в аккаунт) →
   Compose desktop. Каждый поворот оставил свой клиент и свои документы.
3. **Граница общего кода проведена поздно и не до конца.** В `:shared` попали каталог и Connect, но
   владение очередью, радио, цепочка потоков, лайки, кэш, настройки остались в `:app`, поэтому
   Compose desktop пишет их заново или не имеет.
4. **Нет общего интерфейса плеера и хранилища.** `PlaybackManager` и `DesktopPlayer` не имеют
   общего контракта; избранное и история хранятся в трёх форматах.
5. **Нет контроля паритета.** CI запускается вручную и проверяет только Rust/Svelte; Gradle-модули
   в CI не входят. Документы статуса не обновлялись вместе с кодом.

## 8. Целевая архитектура (решено владельцем)

Переписывать с нуля не нужно: Kotlin-часть уже покрывает оба desktop-сценария через `:shared`, а
платформенные адаптеры малы. Целевой Linux-клиент — Compose desktop; Rust/Tauri заморожен — не
развивать и не удалять (решения D1/D2 в `ROADMAP.md`, приняты владельцем). Обоснование: Rust/Svelte-часть (~8,7 тыс. строк Rust и ~2 тыс. строк
Svelte/TS) в основном повторяет логику, уже существующую в Kotlin (InnerTube, верификация, Connect,
очередь), и каждая новая функция стоит двойной реализации; у Compose desktop не хватает
трёх вещей, которые есть в Tauri (вход, библиотека аккаунта, открытие локального файла), и для первых
двух общий код уже написан.

```
:shared commonMain      модели · каталог · QueuePolicy · RadioController · StreamSelector
                        LibraryRepository · CloudLikes · CatalogCache · Settings-модель
:shared jvmCommonMain   OkHttp · резолверы · Connect · OfflineGate
порты (интерфейсы)      AudioEngine · SessionStore · VerificationRuntime* · Discovery*
                        LoginCapture · LibraryStore · SystemMedia · FilePicker
:app                    Media3 · Keystore · WebView · NSD · Room · MediaSession
:desktop                mpv · Secret Service · WebKitGTK · JmDNS · SQLite/JSON · MPRIS
:ui                     общие экраны; навигация и Now Playing остаются платформенными
```
`*` — порт уже существует.

Переносы упорядочены по стоимости и риску в `ROADMAP.md`. Каждый перенос — отдельное изменение с
неизменным поведением Android, подтверждённым существующими тестами.
