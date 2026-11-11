# Kasane — Android Architecture

Документация архитектуры нативного Android-клиента Kasane (`cord.kasane.music`).

---

## 1. Обзор стека

- **Язык**: Kotlin 2.1.10
- **UI Toolkit**: Jetpack Compose (BOM 2025.01.00)
- **Медиа-подсистема**: AndroidX Media3 1.5.1 (`media3-exoplayer`, `media3-session`, `media3-ui`)
- **База данных**: AndroidX Room 2.6.1 с SQLite
- **Сетевой клиент**: OkHttp 4.12.0
- **Изображения**: Coil 2.7.0 (Compose)
- **Целевые версии SDK**: `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`

---

## 2. Модульная организация

Пакет: `cord.kasane.music`

```text
android/app/src/main/java/cord/kasane/music/
├── KasaneApplication.kt         # Application класс, DI синглтоны
├── MainActivity.kt              # Edge-to-Edge Compose Activity
├── auth/
│   ├── AccountSession.kt        # Доменная модель сессии аккаунта
│   ├── AccountRepository.kt     # Управление состоянием аутентификации
│   └── KeystoreSessionStore.kt  # Шифрованное хранилище токенов (Android Keystore / AES-GCM)
├── data/
│   └── KasaneDatabase.kt        # Room Database, TrackEntity, TrackDao
├── model/
│   ├── Track.kt                 # Модель трека и источника воспроизведения
│   └── Catalog.kt               # Модели каталога: BrowsePage, Section, ShelfItem (Song, Album, Artist, Video)
├── playback/
│   ├── AudioRange.kt            # Вычисление ограниченных диапазонов байт (512 KB chunk)
│   ├── YouTubeDataSource.kt     # Bounded Range Media3 DataSource для CDN YouTube
│   ├── QueuePolicy.kt           # Дедупликация и политика очереди воспроизведения
│   ├── PlaybackManager.kt       # Мост между MediaController и Compose StateFlow
│   └── PlaybackService.kt       # Фоновый MediaSessionService (ExoPlayer + MediaSession)
├── provider/
│   ├── GuestPlayerResolver.kt   # Разрешение гостевых аудиопотоков InnerTube (ANDROID_VR + visitorData)
│   ├── StreamResponse.kt        # Парсинг playabilityStatus, streamingData и challenge-ответов
│   ├── MusicProvider.kt         # Поиск, каталоги, радио (watch-next)
│   ├── OnlineCatalog.kt         # Типизированный парсер каталога и полок
│   ├── CatalogParser.kt         # Вспомогательный парсер JSON структур InnerTube
│   ├── ThumbnailParser.kt       # Извлечение обложек высокого разрешения
│   └── AccountLibraryProvider.kt# Контракты работы с библиотекой аккаунта
└── ui/
    ├── KasaneApp.kt             # Главный контейнер Compose, TopBar, Нижняя навигация
    ├── NavigationState.kt       # Управление экранами и стеком навигации
    ├── CatalogScreens.kt        # Экраны детальной информации (Альбом, Артист, Плейлист)
    ├── AccountLibraryScreen.kt  # Экран библиотеки и сохранённых коллекций
    ├── ProfileScreen.kt         # Экран профиля и настроек
    ├── components/
    │   ├── MiniPlayer.kt        # Нижний плавающий мини-плеер
    │   ├── NowPlayingSheet.kt   # Полноэкранный плеер с жестами
    │   ├── TrackRow.kt          # Строка трека в списке
    │   ├── TrackArtwork.kt      # Компонент асинхронной загрузки обложек Coil
    │   └── KasaneChoiceChip.kt  # Чипы категорий и фильтров
    └── theme/
        └── Theme.kt             # Тема, палитра Kasane Rose/Crimson, Sans-Serif шрифты
```

---

## 3. Подсистема воспроизведения (Media3 & YouTubeDataSource)

### 3.1. Архитектура непрерывного воспроизведения
1. **`PlaybackService`** запускается как `MediaSessionService`. Он владеет единственным экземпляром `ExoPlayer` и открывает сессию `MediaSession`. Это гарантирует непрерывное воспроизведение при сворачивании и управление через системную шторку Android / Bluetooth / часы.
2. В очередь ExoPlayer добавляются элементы со стабильным внутренним URI: `kasane://track/<videoId>`.
   - Это предотвращает устаревание временных CDN URL в очереди при паузах или долгих треках.
3. **`YouTubeDataSource`**:
   - Реализует интерфейс Media3 `DataSource`.
   - Перехватывает вызовы к URI `kasane://track/...` в загрузочном потоке Media3.
   - Запрашивает аудиопоток через `GuestPlayerResolver` лениво, ровно в момент, когда плеер начинает декодировать данный трек.
   - Реализует чтение **ограниченными диапазонами байт (Bounded HTTP Range)**.

---

## 4. Гостевое воспроизведение YouTube Music (InnerTube & CDN Range Fix)

### 4.1. Проблема LOGIN_REQUIRED и решение (Visitor Data Handshake)
- Ранее прямые запросы к `youtubei/v1/player` в гостевом режиме приводили к ответу `LOGIN_REQUIRED`.
- Решение (`GuestPlayerResolver.kt`):
  - Клиентский контекст настроен как `ANDROID_VR` (клиент `28`, версия `1.65.10`, User-Agent Oculus).
  - При первом запросе парсится `responseContext.visitorData` (анонимный идентификатор гостевой сессии, выдаваемый самими серверами YouTube).
  - Если возвращается статус вызова гостевой сессии (`StreamResponse.isAnonymousSessionChallenge`), `visitorData` сохраняется в оперативной памяти и немедленно повторяется запрос с заголовком `X-Goog-Visitor-Id`.
  - Повторный запрос успешно отдаёт `playabilityStatus: OK` и прямые аудиопотоки (`adaptiveFormats`).

### 4.2. Проблема HTTP 403 / 410 на CDN и ограниченные Range-запросы
- Серверы Google Audio CDN отклоняют неограниченные запросы (например, `Range: bytes=0-` возвращает `403 Forbidden`).
- Решение (`AudioRange.kt` и `YouTubeDataSource.kt`):
  - Запросы разбиваются на конечные кванты по 512 КБ (`CHUNK_BYTES = 512 * 1024L`).
  - Диапазон передаётся как query-параметр URL: `?range=START-END`.
  - Заголовок `Range` удаляется из запроса, чтобы избежать дублирования смещения.
  - Если CDN возвращает `403` или `410` (истечение срока ссылки стрима), `GuestPlayerResolver.open()` выполняет единичный `forceRefresh`, обновляет URL с сохранением того же `itag` и возобновляет чтение с текущего байтового смещения.

---

## 5. UI и Компоненты

- Все экраны оформлены по спецификации `docs/design-system.md` (Sans-Serif типографика, отсутствие monospace в метаданных, палитра `KasaneRose` `#EF5C7D`).
- Для изоляции и верификации интерфейса без физического устройства настроены Compose Previews, рендерящиеся через Android CLI `android preview render`.
