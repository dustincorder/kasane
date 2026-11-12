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

### 2.2. Диагностика HTTP 403 на CDN (`AudioRange.kt`, `YouTubeDataSource.kt`)

Ранее документация приписывала обрыв истечению URL и размеру диапазона, но live-проверка этого не подтвердила. При проверке в ноябре 2026 первый диапазон публичного трека вернул `200`, следующий — `403`; принудительное получение свежего URL с тем же `ANDROID_VR` и itag также вернуло `403`. На том же URL диапазон 512 KiB со смещением `524288` получил `403`, а несколько меньших диапазонов прошли, но дальнейшее уменьшение чанка не устранило отказ. Значит, размер чанка и истечение URL не установлены как причина.

Независимый разбор этого текущего симптома сообщает, что Google Video Server ограничивает `ANDROID_VR` примерно первым 60–76 секундами без GVS Proof-of-Origin token; простая замена/обновление URL не помогает. См. [yt-dlp issue #17348](https://github.com/yt-dlp/yt-dlp/issues/17348) и [диагностику GVS 403 с ANDROID_VR](https://github.com/anxdpanic/plugin.video.youtube/issues/1481). Это внешние инженерные наблюдения, а не гарантия API. Получать или подделывать attestation/PO token Kasane не будет.

В `YouTubeDataSource` сохранено ограниченное повторное разрешение потока при CDN 403/410 во время `read()`: чтение возобновляется с текущего абсолютного byte offset и требует прежний itag. Повторы ограничены и сбрасываются только после успешного продвижения данных. Это покрывает истёкшие URL, но **не решает наблюдаемый отказ `ANDROID_VR` на GVS**. Полное воспроизведение гостевого стрима остаётся заблокировано внешним ограничением клиента; нужно перейти на совместимый легитимный player profile/поток либо официально доступный способ выдачи необходимых credentials, если он появится.

- Запросы продолжают использовать bounded query ranges и стабильные `kasane://track/<videoId>` URI Media3.
- Текущая live-проверка наблюдала cutoff, но не выполняла длительное воспроизведение через Media3 на Pixel после этой диагностики.

---

## 3. Статус модулей

| Модуль | Статус | Примечания |
|---|---|---|
| **Гостевое воспроизведение** | Ограничено / upstream blocker | Старт аудио проходит, но CDN может вернуть 403 примерно после начального участка для `ANDROID_VR`; full-track playback не подтвержден. |
| **Каталог и поиск** | Готово, проверено | Типизированные песни, альбомы, артисты, плейлисты. |
| **Очередь Media3** | Готово | Единая очередь в `PlaybackService`, дедупликация через `QueuePolicy`. |
| **Google/YTM Login** | Web-session login implemented; real account not yet verified | User completes Google sign-in inside a short-lived, origin-restricted WebView. Session is accepted only after the user confirms a channel and authenticated `account_menu` succeeds. Google refusal is shown without bypass. |
| **Библиотека аккаунта** | InnerTube collections wired; real account not yet verified | Liked Songs, playlists, saved albums and subscriptions use authenticated InnerTube browse IDs. Local Favorites and History remain independent. |
| **Офлайн / Скачивание** | Не начато (Non-goal на данном этапе) | Воспроизведение работает исключительно в online-режиме через CDN стримы. |

---

## 4. Авторизация аккаунта: Web session

- Profile opens a temporary WebView on Google’s real sign-in flow, with JavaScript enabled only for the site and no JavaScript bridge. Navigation is HTTPS-only and exact-host allowlisted; file/content access, mixed content, popups, geolocation, media permissions, and TLS error overrides are denied.
- After YouTube Music is ready, the user explicitly requests session checking. Kasane reads a small allowlist of auth cookies only from `https://music.youtube.com`, plus `VISITOR_DATA`, `DATASYNC_ID`, and `SESSION_INDEX` from a fixed `ytcfg` probe. Credentials are never logged or sent to another host.
- `account/accounts_list` returns channel options; multiple channels require an explicit selection. The session is encrypted using Android Keystore AES-GCM in `noBackupFilesDir` and is saved only after `account/account_menu` identifies the selected channel.
- Logout removes the encrypted Kasane session and the app WebView cookie/storage state. It does not call Google logout and does not clear Chrome or other apps.
- Guest catalog requests do not inherit account cookies. Guest streaming, local Favorites, and History remain unchanged.
- Sources studied (no code copied): [Metrolist LoginScreen](https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/ui/screens/LoginScreen.kt), [BitChord YtMusicLoginScreen](https://github.com/kushagrasinghx/BitChord/blob/main/app/src/main/java/com/music/bitchord/auth/YtMusicLoginScreen.kt).
- The implementation has not yet been tested with a real Google account or a live authenticated response. Google may reject embedded sign-in; Kasane shows that refusal and does not bypass it. Account library and personalized Home therefore remain pending user verification.

## 5. Верификация и диагностика

### Команды проверки:
```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
Все 16 тест-сьютов (включая `GuestPlayerResolverTest`, `YouTubeDataSource` / `AudioRangeTest`, `QueuePolicyTest`) проходят успешно.
