# Kasane — Agent Map

Карта кодовой базы Kasane (カサネ) для AI-агентов (Codex, Antigravity CLI) и разработчиков.
Включает актуальное сопоставление подсистем, модулей, классов, интерфейсов и маршрутов.

---

## 1. Обзор репозитория

| Подсистема | Путь | Стек | Описание |
|---|---|---|---|
| **Android Client** | `android/` | Kotlin 2.1.10, Jetpack Compose, Media3 1.5.1, Room 2.6.1 | Нативное Android-приложение (`cord.kasane.music`). |
| **Desktop Client** | `src/`, `src-tauri/` | Svelte 5, Tailwind CSS, Tauri 2, Rust | Десктоп-клиент для Linux (`cord.kasane.desktop`). |
| **Audio Engine** | `crates/vibe-audio/` | Rust, rodio / cpal | Воспроизведение, очередь, буферизация. |
| **Core Domain** | `crates/vibe-core/` | Rust | Модели данных (`Track`, `Playlist`, `Artist`), общие структуры. |
| **Database** | `crates/vibe-db/` | Rust, SQLite / sqlx | Локальная БД истории, кэша и метаданных десктопа. |
| **Providers** | `crates/vibe-providers/`| Rust, reqwest, serde | Скрейпинг YouTube Music (InnerTube), резолв аудиопотоков. |

---

## 2. Android (`android/app/src/main/java/cord/kasane/music`)

Базовый пакет: `cord.kasane.music`
Application ID: `cord.kasane.music`

### 2.1. Точка входа и DI
- `KasaneApplication.kt`:
  - Синглтоны: `KasaneDatabase`, `OkHttpClient`, `GuestPlayerResolver`, `MusicProvider`, `PlaybackManager`, `AccountRepository`.
- `MainActivity.kt`:
  - `MainActivity : ComponentActivity()`: Edge-to-Edge окно, запуск `KasaneApp()`.

### 2.2. Воспроизведение (`playback/`)
- `provider/sabr/SabrWire.kt`, `SabrSession.kt`: ограниченный SABR/UMP AAC/fMP4 транспорт, ephemeral context, server protection handling.
- `playback/SabrDataSource.kt`: внутренний on-demand HLS/fMP4 bridge для существующего Media3, без файлов/HTTP proxy.
- Статус и ограничения: `docs/sabr-playback.md` (полное YouTube-воспроизведение ещё не подтверждено).
- `GuestPlayerResolver.kt`:
  - Класс: `GuestPlayerResolver(client: OkHttpClient)`
  - Резолв гостевых потоков через клиент `ANDROID_VR` (`1.65.10`) с автоматическим `visitorData` handshake.
  - Метод `open(videoId, operation)`: прозрачный единичный `forceRefresh` при HTTP 403/410 от CDN.
- `AudioRange.kt`:
  - Квантование HTTP Range-запросов (`CHUNK_BYTES = 512KB`).
  - Формирование URL с query-параметром `?range=START-END`.
- `YouTubeDataSource.kt`:
  - Реализация `androidx.media3.datasource.DataSource`.
  - Ленивое разрешение `kasane://track/<id>` потоков в треде Media3 и чтение квантованными кусками.
- `PlaybackService.kt`:
  - `PlaybackService : MediaSessionService()`: ExoPlayer, MediaSession, системное уведомление воспроизведения.
- `PlaybackManager.kt`:
  - Управление воспроизведением из Compose через `MediaControllerCompat`/`MediaController`.
- `QueuePolicy.kt`:
  - Политика дедупликации и управления порядком очереди воспроизведения.

### 2.3. Провайдеры и Каталог (`provider/`, `model/`)
- `MusicProvider.kt`:
  - Поиск (`search`), домашняя лента (`home`), стримы и радио (`watch-next`).
- `OnlineCatalog.kt`, `CatalogParser.kt`:
  - Парсинг ответов InnerTube (песни, альбомы, артисты, плейлисты, видео).
- `ThumbnailParser.kt`:
  - Парсинг превью и обложек высокого разрешения.
- `AccountLibraryProvider.kt`:
  - Заготовки взаимодействия с пользовательской библиотекой (Liked songs, Playlists).
- `Catalog.kt`, `Track.kt`:
  - Доменные модели: `Track`, `BrowsePage`, `Section`, `ShelfItem`.

### 2.4. Хранилище и Сессии (`data/`, `auth/`)
- `KasaneDatabase.kt`:
  - Room Database (`recent_tracks`, `TrackDao`).
- `AccountSession.kt`, `AccountRepository.kt`:
  - Состояние аккаунта (Guest / Authenticated).
- `KeystoreSessionStore.kt`:
  - Безопасное хранение токенов через Android Keystore (AES-GCM).

### 2.5. UI и Компоненты (`ui/`)
- `KasaneApp.kt`: Главный контейнер (Home, Search, Library, Profile).
- `NavigationState.kt`: Управление экранами и историей переходов.
- `CatalogScreens.kt`: Детальные экраны артистов, альбомов и плейлистов.
- `AccountLibraryScreen.kt`: Экран библиотеки пользователя.
- `ProfileScreen.kt`: Экран профиля и настроек.
- `components/`:
  - `MiniPlayer.kt`: Нижний плавающий мини-плеер с полосой прогресса.
  - `NowPlayingSheet.kt`: Развёрнутый плеер текущего трека с жестами.
  - `TrackRow.kt`: Строка элемента списка трека с обложкой.
  - `TrackArtwork.kt`: Оптимизированный компонент обложки (Coil).
  - `KasaneChoiceChip.kt`: Акцентные чипы выбора и категорий.
- `theme/Theme.kt`: Палитра `KasaneRose` (`#EF5C7D`), `KasaneCrimson` (`#D52F58`), Sans-Serif шрифты.

---

## 3. Desktop Client (`src/` & `src-tauri/`)

- Application ID: `cord.kasane.desktop`
- `src/`: Svelte 5 + Tailwind CSS UI.
- `src-tauri/`: Tauri 2 backend на Rust.
- `crates/`: `vibe-audio`, `vibe-core`, `vibe-db`, `vibe-providers`.

- Playlist destination parsing and confirmed account mutation: `android/app/src/main/java/cord/kasane/music/provider/PlaylistActions.kt`.
- Account-scoped picker state and lost-acknowledgement recovery: `android/app/src/main/java/cord/kasane/music/provider/PlaylistPicker.kt`.
- Shared native playlist modal: `android/app/src/main/java/cord/kasane/music/ui/components/PlaylistPickerSheet.kt`.
- Malformed account-library response guard: `android/app/src/main/java/cord/kasane/music/provider/AccountLibraryParser.kt`.
