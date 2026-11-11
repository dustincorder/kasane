# Android Online & Guest Playback Status — 2026-11-11

Документ фиксирует текущее подтверждённое состояние гостевого воспроизведения, поиска, каталога и аутентификации в нативном Android-клиенте Kasane (`cord.kasane.music`).

---

## 1. Подтверждённый статус и ключевые достижения

- **Гостевое воспроизведение работает стабильно на физическом устройстве (Pixel 6 Pro)**:
  - Подтверждено реальным воспроизведением трека *Sabrina Carpenter — Espresso* без необходимости авторизации в Google аккаунте.
  - Поиск, домашний каталог (Quick Picks / Shelves) и загрузка обложек треков через Coil функционируют в реальном времени.

---

## 2. Архитектура решения проблем воспроизведения

### 2.1. Решение проблемы LOGIN_REQUIRED (`GuestPlayerResolver.kt`)
1. **Симптом**: При попытке запросить стрим для некоторых треков InnerTube возвращал ошибку `LOGIN_REQUIRED`.
2. **Первопричина**: YouTube требует контекст гостевой сессии (`visitorData`) для легитимизации клиента.
3. **Реализация**:
   - Клиент идентифицируется как `ANDROID_VR` (версия `1.65.10`, User-Agent Oculus Quest).
   - При первом вызове к `youtubei/v1/player` YouTube возвращает ответ с `responseContext.visitorData`.
   - Если плеер возвращает вызов анонимной сессии (`isAnonymousSessionChallenge`), `GuestPlayerResolver` извлекает `visitorData`, сохраняет его в памяти и мгновенно повторяет запрос с заголовком `X-Goog-Visitor-Id`.
   - Второй запрос возвращает статус `OK` с валидными `adaptiveFormats`.
   - **Безопасность**: Данные `visitorData` являются временными и анонимными; токены аккаунтов, cookies и секреты не логируются и не сохраняются на диск.

### 2.2. Решение проблемы HTTP 403 / 410 на CDN (`AudioRange.kt`, `YouTubeDataSource.kt`)
1. **Симптом**: При запросе медиаданных с CDN серверов Google возвращался код ошибки `403 Forbidden` или `410 Gone`.
2. **Первопричина**:
   - Аудио-CDN YouTube блокирует безлимитные HTTP Range-запросы (например, `Range: bytes=0-`).
   - Истекает срок действия временного URL стрима (параметр `expire`).
3. **Реализация**:
   - **Bounded Range Chunks**: Запросы квантуются по 512 КБ (`CHUNK_BYTES = 512 * 1024L`).
   - Диапазон байт передаётся непосредственно в query-параметре URL: `?range=START-END`. Заголовок `Range` удаляется из запроса, предотвращая двойное смещение.
   - **Автоматический Refresh при 403/410**: Метод `resolver.open(id)` при перехвате `AudioHttpException(403 | 410)` выполняет единичный принудительный запрос нового URL стрима с сохранением того же `itag` и возобновляет чтение с текущей позиции.
   - **Совместимость с Media3**: `YouTubeDataSource` интегрирован в `ExoPlayer` через кастомный `DataSource.Factory`. В очереди плеера используются стабильные URI `kasane://track/<videoId>`.

---

## 3. Статус модулей

| Модуль | Статус | Примечания |
|---|---|---|
| **Гостевое воспроизведение** | Готово, проверено на устройстве | Работает через `GuestPlayerResolver` + `YouTubeDataSource`. |
| **Каталог и поиск** | Готово, проверено | Типизированные песни, альбомы, артисты, плейлисты. |
| **Очередь Media3** | Готово | Единая очередь в `PlaybackService`, дедупликация через `QueuePolicy`. |
| **Google/YTM Login** | Заблокировано неподдерживаемым способом получения сессии | Android Keystore, проверка и сохранение сессии — только заготовки; в приложении нет процесса входа. Официальный Google OAuth не выдаёт web-session cookies, которые требует текущий InnerTube account provider. Profile объясняет ограничение; Kasane не перехватывает cookies браузера и не принимает пароль. |
| **Библиотека аккаунта** | Заблокировано отсутствием логина | В гостевом режиме запросы отклоняются; локальная база Room работает независимо. |
| **Офлайн / Скачивание** | Не начато (Non-goal на данном этапе) | Воспроизведение работает исключительно в online-режиме через CDN стримы. |

---

## 4. Авторизация аккаунта: исследование и блокер

- Google поддерживает авторизацию нативных приложений через внешний браузер и OAuth scopes для YouTube Data API. Это даёт доступ только к документированным YouTube API ресурсам и не создаёт SAPISID cookie или другую InnerTube web session.
- Текущий `OnlineCatalog` подписывает account-запросы через `SAPISID`/`__Secure-3PAPISID`, `authUser` и, для некоторых действий, `delegatedSessionId`. Обычный Google ID/access token нельзя безопасно преобразовать в эти данные.
- Актуальные Metrolist и BitChord получают InnerTube session из cookie jar встроенного WebView. Limusic описывает in-app web login/cookie-paste и использует webview для части InnerTube-поведения. Это неподдерживаемые web-session integrations; Kasane не переносит их код и не внедряет cookie capture. Google OAuth policy запрещает направлять OAuth authorization в embedded user-agent, который контролирует приложение.
- Источники исследования: [Metrolist LoginScreen](https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/ui/screens/LoginScreen.kt), [BitChord YtMusicLoginScreen](https://github.com/kushagrasinghx/BitChord/blob/main/app/src/main/java/com/music/bitchord/auth/YtMusicLoginScreen.kt), [Limusic README](https://github.com/SimoHypers/limusic), [Google OAuth 2.0 Policies](https://developers.google.com/identity/protocols/oauth2/policies), [YouTube Data API authorization](https://developers.google.com/youtube/v3/guides/authentication).
- Код этих проектов в Kasane не копировался; лицензии не использовались как основание для переноса реализации.
- Поэтому реальный вход в аккаунт и облачная библиотека остаются заблокированы. Нужен поддерживаемый Google API с нужными YouTube Music данными либо официальный безопасный механизм получения именно такой сессии. До этого Profile явно показывает ограничение, а локальные избранное/история и гостевое воспроизведение остаются доступны.
- Проверки проекта не включают реальную авторизацию Google или доступ к персональной библиотеке.

## 5. Верификация и диагностика

### Команды проверки:
```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
Все 16 тест-сьютов (включая `GuestPlayerResolverTest`, `YouTubeDataSource` / `AudioRangeTest`, `QueuePolicyTest`) проходят успешно.
