# Kasane — Codex Handoff

> **Historical handoff — current playback limitation:** the earlier “fully works”
> wording below records the initial short Pixel test, not full-catalog reliability.
> Current measured SABR-only playback stops around 69.845 seconds while the server
> reports pending attestation. Three real full-track Media3 runs have not passed.
> See [SABR evidence](sabr-playback.md) and [online status](android-online-status.md).

Документ передачи контекста для продолжения разработки **OpenAI Codex CLI** (основной coding agent проекта Kasane).

---

## 1. Текущее состояние проекта

- **Продукт**: Музыкальный плеер Kasane (カサネ).
- **Пакет Android**: `cord.kasane.music`.
- **Ветка**: `main`.
- **Статус гостевого воспроизведения**: **ПОЛНОСТЬЮ РАБОТАЕТ**.
  - На физическом устройстве (Pixel 6 Pro) успешно воспроизводится реальный звук (проверено на *Sabrina Carpenter — Espresso*).
  - Работает быстрый поиск, домашняя витрина (Home / Quick Picks), экраны альбомов, исполнителей и плейлистов, загрузка обложек Coil.
  - Успешно компилируются Debug APK (`./gradlew assembleDebug`) и тестовые сьюты (`./gradlew testDebugUnitTest`).

---

## 2. Что было сделано и решено последним Codex

1. **Решение `LOGIN_REQUIRED`**:
  - Внедрён класс [`GuestPlayerResolver.kt`](../android/app/src/main/java/cord/kasane/music/provider/GuestPlayerResolver.kt) с клиентом `ANDROID_VR` (`1.65.10`, User-Agent Oculus Quest).
   - Реализован handshake гостевой сессии: при первом запросе извлекается `responseContext.visitorData` и немедленно отправляется повторный запрос с заголовком `X-Goog-Visitor-Id`, отдающий валидные аудиопотоки.
2. **Решение HTTP 403 / 410 на CDN YouTube**:
   - Внедрён [`AudioRange.kt`](../android/app/src/main/java/cord/kasane/music/playback/AudioRange.kt) и [`YouTubeDataSource.kt`](../android/app/src/main/java/cord/kasane/music/playback/YouTubeDataSource.kt).
   - Чтение потока разбито на ограниченные кванты по 512 КБ с передачей `?range=START-END` в URL.
   - Реализован единичный прозрачный refresh стрима при ошибках 403 / 410.
3. **Стабилизация очереди Media3**:
   - В очереди ExoPlayer используются стабильные идентификаторы `kasane://track/<videoId>`. Потоки резолвятся лениво при наступлении очереди трека в загрузочном потоке Media3.
   - Политика очереди вынесена в [`QueuePolicy.kt`](../android/app/src/main/java/cord/kasane/music/playback/QueuePolicy.kt).
4. **Безопасность**:
   - Никаких захардкоженных URL, cookies, токенов конкретного пользователя или паролей в коде нет. Токены сессии защищаются в Android Keystore через AES-GCM (`KeystoreSessionStore.kt`).

---

## 3. Что нельзя случайно сломать (Критические инварианты)

> [!CAUTION]
> **НЕ ИЗМЕНЯЙТЕ И НЕ УДАЛЯЙТЕ СЛЕДУЮЩИЕ УЗЛЫ БЕЗ ВЕСКИХ ПРИЧИН:**
1. **`GuestPlayerResolver.kt`**:
   - Не меняйте клиент `ANDROID_VR` и логику повторного запроса при `isAnonymousSessionChallenge` с `visitorData`. Это ключ к обходу `LOGIN_REQUIRED`.
2. **`AudioRange.kt` и `YouTubeDataSource.kt`**:
   - Не отправляйте неограниченный заголовок `Range: bytes=0-` — Google CDN мгновенно ответит 403 Forbidden.
   - Сохраняйте передачу диапазона через query-параметр `?range=START-END` и квантование по 512 КБ.
3. **Стабильные URI в очереди Media3**:
   - В очереди `ExoPlayer` должны быть только `kasane://track/<id>`, а не прямые CDN-ссылки (они истекают через несколько часов).
4. **Дизайн-система**:
   - Основной акцент: `KasaneRose` (`#EF5C7D`), `KasaneCrimson` (`#D52F58`).
   - Шрифты: строго `FontFamily.SansSerif`. Никакого моноширинного шрифта в названиях треков и временных метках.

---

## 4. Ключевые файлы и пути

- **Воспроизведение и стримы**:
  - `android/app/src/main/java/cord/kasane/music/provider/GuestPlayerResolver.kt`
  - `android/app/src/main/java/cord/kasane/music/playback/YouTubeDataSource.kt`
  - `android/app/src/main/java/cord/kasane/music/playback/AudioRange.kt`
  - `android/app/src/main/java/cord/kasane/music/playback/PlaybackService.kt`
  - `android/app/src/main/java/cord/kasane/music/playback/PlaybackManager.kt`
  - `android/app/src/main/java/cord/kasane/music/playback/QueuePolicy.kt`
- **Каталог и парсинг**:
  - `android/app/src/main/java/cord/kasane/music/provider/MusicProvider.kt`
  - `android/app/src/main/java/cord/kasane/music/provider/OnlineCatalog.kt`
  - `android/app/src/main/java/cord/kasane/music/provider/StreamResponse.kt`
- **UI и экраны**:
  - `android/app/src/main/java/cord/kasane/music/ui/KasaneApp.kt`
  - `android/app/src/main/java/cord/kasane/music/ui/components/MiniPlayer.kt`
  - `android/app/src/main/java/cord/kasane/music/ui/components/NowPlayingSheet.kt`
  - `android/app/src/main/java/cord/kasane/music/ui/components/TrackRow.kt`
  - `android/app/src/main/java/cord/kasane/music/ui/theme/Theme.kt`

---

## 5. Как верифицировать изменения

Перед сдачей любой задачи обязательно запускайте:
```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
При изменениях в UI:
```bash
android preview render \
  --fqn=cord.kasane.music.ui.components.TrackRowKt.TrackRowPreview \
  --fqn=cord.kasane.music.ui.components.MiniPlayerKt.MiniPlayerPreview \
  --fqn=cord.kasane.music.ui.KasaneAppKt.HomeScreenPreview \
  --output=build/previews :app
```

---

## 6. Приоритетный план следующих задач для Codex

1. **Стабильность гостевого воспроизведения**:
   - Обработка смены сети (Wi-Fi ↔ Mobile Data) и плавный retry при таймаутах сокетов.
   - Обработка Audio Focus (пауза при звонках, приглушение при уведомлениях навигаторов).
2. **UX-улучшения главного экрана и плеера**:
   - Секция «Слушать снова» (Listen Again) на базе локальной базы Room (`KasaneDatabase`).
   - Исключение дублирования одного и того же трека на главном экране.
   - Добавление управления громкостью/эквалайзером или расширенными действиями с очередью в `NowPlayingSheet`.
3. **Авторизация в аккаунте YouTube Music**:
   - Реализация безопасного acquisition flow (аутентификация через проверенный браузерный механизм без ввода логина/пароля в непроверенные поля).
   - Синхронизация избранных треков (Liked Songs) и плейлистов пользователя.
4. **Офлайн-режим (отложено на более поздний этап)**:
   - Кэширование аудиопотоков на диск пока НЕ реализовывать, сосредоточиться на онлайн-стабильности.
