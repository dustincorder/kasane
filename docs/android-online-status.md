> Обновление транспорта: добавлен SABR/UMP → native Media3 для clear AAC/fMP4 VOD. Эмуляторный тестовый источник проигран полностью; реальный IOS SABR `JALbemLw3G4` остановился на 69,845 мс со статусом attestation pending. Это не полное исправление YouTube playback. См. [проверки и ограничения](sabr-playback.md). Предыдущие наблюдения ниже сохранены как история диагностики.

# Android Online & Guest Playback Status — 2026-11-11

Документ фиксирует текущее подтверждённое состояние гостевого воспроизведения, поиска, каталога и аутентификации в нативном Android-клиенте Kasane (`cord.kasane.music`).

---

## 1. Подтверждённый статус и ключевые достижения

- **Гостевое воспроизведение ранее подтверждалось на Pixel 6 Pro** для *Sabrina Carpenter — Espresso*. Последующая диагностика в ноябре 2026 выявила воспроизводимый риск HTTP 403 после начального диапазона для текущего профиля; стабильное воспроизведение полного трека сейчас не подтверждено. Поиск, Home/Quick Picks и обложки ранее проверялись на устройстве.

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

## 6. Профили InnerTube — независимая гостевая проверка, 2026-11-12

Проверка выполнена с Linux-хоста, без Google/YTM credentials и без сохранения streaming URL. Вызовы были ограничены контрольными публичными ID Espresso (`kIft-LUHHVA`), Let Down (`ZVgHPSyEIqk`), Beat It (`kOn-HdEg6AQ`) и Never Gonna Give You Up (`dQw4w9WgXcQ`). HTTP 200 у player endpoint сам по себе не считался playable result.

| Профиль | Наблюдение | Проверка потока |
|---|---|---|
| `ANDROID_VR` 1.65.10 | На Espresso ответил `LOGIN_REQUIRED`, playable formats отсутствовали. | URL для CDN проверки не выдан. |
| `ANDROID` 21.26.364 | Для всех четырёх контрольных ответ `OK` с audio format metadata; audio formats не содержали прямых HTTPS URL или `signatureCipher`. Ответ содержал `serverAbrStreamingUrl` — протокол SABR/UMP здесь не реализован Kasane. | Нет обычного URL для byte-range теста. |
| `IOS` 21.26.4 | `OK` и audio metadata; для Never Gonna Give You Up также вернулся HLS manifest. Остальным трём из этой четвёрки HLS не вернулся. | У Never Gonna manifest и media playlist ответили 200; 38 последовательных сегментов (213.04 с, 5,016,592 байта) были прочитаны в памяти, включая сегмент около 60 с. Все сегменты — HTTP 200. Ничего не сохранялось на диск, cookies не передавались. |
| `WEB_MUSIC` / `WEB_REMIX` | Для каждого из четырёх гостевых запросов `UNPLAYABLE`, аудио- и HLS-форматов нет. | Не проверялся. |
| `WEB`, `MWEB`, `WEB_SAFARI`, `TV`, `TV_SIMPLY`, `WEB_EMBEDDED` | Для Espresso гостевой ответ не содержал применимого direct/HLS аудио; статусы были `UNPLAYABLE`, `LOGIN_REQUIRED` или `ERROR` в зависимости от профиля. | Не проверялся, когда пригодного URL не было. |
| `ANDROID_MUSIC` | Отсутствует в текущем поддерживаемом списке yt-dlp; не был выдуман/запрошен как будто это текущий профиль. | Не применимо. |

Актуальный yt-dlp сообщает, что `ANDROID_VR` версии 1.65.10 получает GVS PoToken enforcement для HTTPS/DASH и что с 2026-08-17 observed 403 распространился на все форматы. Для `ANDROID` HTTPS/DASH также требует GVS PoToken, но в этих ответах сервер вернул SABR metadata без прямых URL. `WEB_MUSIC` требует GVS PoToken для HTTPS/DASH (с documented Premium exception), HLS policy отличается. См. [текущую матрицу yt-dlp](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py) и [PO Token guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide).

InnerTubeX предлагает SABR/UMP, direct/HLS selection, cipher handling и token-provider contracts, но README указывает GPL-3.0. Kasane не добавлял его как зависимость и не копировал реализацию; текущий repository license не выбран. Документация проекта также называет несколько client IDs probes, а не подтверждёнными playback fallbacks. См. [InnerTubeX README и лицензию](https://github.com/MetrolistGroup/innertubex).

Это подтверждает HLS транспорт в независимой гостевой HTTP-проверке для одной композиции, но **не подтверждает Media3 end-to-end playback**. На момент той сетевой проверки кандидат ещё не был интегрирован. В следующей итерации добавлен native Media3 HLS MediaSource и выполнено полное воспроизведение 75-секундного синтетического HLS на изолированном API 35 эмуляторе, включая seek/pause/next и ошибки сегментов. Реальный YouTube HLS в эмуляторе пока не подтверждён из-за UnknownHostException до получения манифеста; это не исправление всего каталога. См. [HLS playback](hls-playback.md). User Pixel после инцидента не использовался. Подписанная YTM-сессия не читалась и не передавалась.
