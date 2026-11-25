# Известные проблемы

Аудит 2026-11-23. Каждая запись подтверждена чтением кода или запуском; где подтверждения нет,
стоит пометка «риск». Закрытую запись удаляй вместе с правкой `FEATURE_MATRIX.md`.

## Процесс и состояние репозитория

- **KI-1. Целевой desktop-клиент не определён.** `docs/architecture/multiplatform.md` называет Tauri
  «no longer the target», `compose-desktop.md` — «remains the shipped Linux client», `AGENTS.md` —
  основной, а Compose экспериментальный. У каждого клиента есть функции, которых нет у другого.
- **KI-2. Старые документы противоречат коду.** `STATUS.md` (10.11: «13 IPC commands», фактически 37);
  `agent-map.md` и `architecture/linux.md` (sqlx — фактически rusqlite; `YouTubeDataSource` и
  `GuestPlayerResolver` описаны как путь воспроизведения Android — не используются); `README.md`
  («Offline downloads: Planned» — код есть); `DECISIONS.md` ADR-003 (`ANDROID_MUSIC` — в коде нет);
  палитра Rose в старом `development.md` и навыке `kasane-ui` против синей в `AGENTS.md`.
  `ACCEPTANCE.md`: отмечен 1 пункт из 52.
- **KI-3. Незакоммиченные изменения до аудита.** 4 файла в `desktop-compose/` (подстраница Kasane
  Connect в настройках). Компилируются, функционально не проверены, аудитом не тронуты.
- **KI-4. Неслитая работа.** Ветка `integ/devin-stack` (worktree `~/kasane-integ`) содержит 7 коммитов,
  которых нет в текущей ветке; `main` отстаёт от текущей ветки на 46 коммитов; есть stash на `main`.
- **KI-5. CI не защищает от регрессий.** `.github/workflows/ci.yml` запускается только вручную и
  покрывает Rust/Svelte. Gradle-модули (Android, shared, desktop) в CI не входят.
- **KI-6. Версии рассогласованы.** Активные клиенты выровнены на `0.2.0-alpha` (Android
  `versionName`, Compose desktop `packageVersion`); замороженные артефакты отстают:
  `package.json` 0.1.0-alpha, Tauri/Cargo 0.1.1-alpha, `kasane-connect` 0.1.0.

## Расхождения между клиентами

- **KI-7. Три цепочки выбора потока.** Android: WEB_REMIX с сессией → SABR → верифицированный →
  iOS HLS (сохранение идёт отдельной веткой `resolveForSave`: сессионный progressive →
  верифицированный, без HLS/SABR). Tauri: верифицированный → iOS HLS. Compose: верифицированный → iOS HLS → гостевой;
  его `resolveForSave` приведён к семантике Android — только верифицированный поток, без
  гостевого фолбэка (гостевые URL обрываются YouTube ~через 29 с, сохранять их нельзя).
  Запрос iOS HLS продублирован в Kotlin (`HlsPlayerResolver.resolveIos` в `:app` и `IosHlsResolver`
  в `:shared`) и в Rust. Версии клиентов YouTube зашиты строками в нескольких местах
  (`1.20240101.01.00`, `1.20250310.01.00`, `1.20260707.12.00`, `21.26.4`) — при устаревании править
  нужно везде.
- **KI-8. Радио реализовано дважды.** `PlaybackService.fillRadio` и `DesktopPlayer.loadRadio`: разные
  пороги дозагрузки, Compose игнорирует настройку Autoplay (её там нет). В Tauri радио нет.
  Общая политика (`S/playback/QueuePolicy.kt`) теперь покрывает и якорный запуск
  (`anchoredRadio` — «радио текущего трека» не перезапускает его, desktop уже использует);
  Android пока ходит собственным путём — см. KI-36.
- **KI-9. История пишется по-разному.** Android — при фактическом старте звука; Tauri — при выборе
  трека, `duration_listened_secs` всегда 0; Compose — при загрузке, до старта. Частично закрыто:
  общий порог `S/playback/PlayThreshold.kt` (30 с или половина короткого трека) теперь единый для
  локальной записи и YTM watch-report (см. KI-35); Android и Tauri пишут по-прежнему по-своему.
- **KI-11. Kotlin↔Rust Connect не проверяется по умолчанию.** `ConnectInteropTest` пропускается без
  `KASANE_RUST_CLI`. Совместимость держится на `test-vectors.json` и ручных запусках.

## Android

- **KI-12. Миграции Room не тестируются.** `exportSchema = false`, тестов миграций нет, а версий
  уже 7. Ошибка в миграции = потеря избранного и истории у пользователя.
- **KI-13. Настройка `autoplay` мимо `AppSettings`.** Читается напрямую из SharedPreferences
  `playback` в `PlaybackService`, `KasaneApp`, `SettingsScreen`.
- **KI-14. Мёртвый код.** `YouTubeDataSource.kt` уже удалён; `MusicProvider.resolveStreamUrl`/
  `getHomeRecommendations`/`playerResolver` ещё существуют (`MusicProvider.kt`), использование
  требует перепроверки. `InnerTuneBackupAdapter`, зависимости
  `navigation-compose` и `lifecycle-viewmodel-compose`.
- **KI-15. `KasaneApp.kt` — 926 строк.** Состояние навигации, загрузка Home и связывание сервисов
  в одном composable; эту логику нельзя ни протестировать отдельно, ни разделить с desktop.
- **KI-16. Риск: опрос плеера.** `PlaybackManager` каждые 400 мс пересобирает весь список очереди
  (до 2000 элементов). Не измерялось.
- **KI-17. SABR не подтверждён** на реальном треке (`docs/sabr-playback.md`).
- **KI-18. Release-подпись** читается из `~/.glazegram/` (имя другого проекта). `assembleRelease`
  в аудите не запускался.

## Compose desktop

- ~~**KI-32. Desktop был невидим для Android в Connect discovery.**~~ Исправлено:
  `MdnsResponder.questions()` сравнивал qtype без маски — NSD шлёт QU-запросы (бит 0x8000),
  `0x800C` не совпадал с `PTR(12)` → responder молчал. Теперь qtype маскируется `and 0x7FFF`.
  Плюс анонс был одноразовым (3× на старте): добавлен периодический re-announce каждые 60 с
  (SRV/A TTL — 120 с) и перебиндинг JmDNS+responder при смене LAN-интерфейса/адреса
  (`DesktopConnect` — poll-цикл проверяет `lanAddress()`). Проверка: `C:MdnsResponderTest`
  (QU-запрос → PTR/ANY), приём чужих анонсов не тронут.
- ~~**KI-33. Transfer между устройствами шёл вне арбитра.**~~ `DesktopRemote.transferTo`
  переведён на нормативные команды протокола: `manager.transferTo` (`takeover` → ack → локальная
  пауза, без двойного воспроизведения), `manager.claimPerformer()` (`yield` на активного +
  локальный старт), remote→remote — `takeover` цели + `yield` источнику с `activeId`. Локальный
  старт воспроизведения теперь делает claim (`yield` активному исполнителю), а не просто pause.
  Проверка: `C:DesktopPlayerAdapterTest` (loopback remote-команд: playTrack/pause/seek/volume/
  queue/takeover-handoff/unsupported), `--connect-smoke` зелёный (transfer B с позиции, A затих).
- ~~**KI-21. Нет тестов на `StreamProxy`, `MpvEngine`.**~~ Закрыто: `StreamProxyTest`
  (HTTP-обмен против локального сервера: range-проксирование, отказ CDN, tee-кэш),
  `StreamSelectorTest` (цепочка `resolve`/`resolveForSave` без гостевого фолбэка на сохранение),
  `MpvEngineTest` (реальный mpv по JSON IPC: load/play/seek/pause/end на сгенерированном WAV;
  пропускается там, где нет бинаря `mpv`). В `:ui` тестов по-прежнему нет.
- ~~**KI-31. jpackage-лаунчер падает при каждом запуске (SIGABRT, core dump).**~~ Причинно
  установлено (strace + coredumpctl): AppLauncher форкает дитя для package-detection
  (`rpm -qf`/`dpkg -S`), и при его `exit()` ld.so дёргает `Logger::log` на уже разрушенном
  объекте → `__cxa_pure_virtual` → abort → дамп на КАЖДЫЙ запуск бинаря (KDE показывал краш-
  уведомление при каждом спавне verifier/login-хелпера — т.е. при переключении треков; второй
  дамп шёл из teardown основного процесса). Баг в `libapplauncher.so` JDK 21.0.13, из JVM не
  лечится. Обход: `createDistributable` кладёт `bin/java` из toolchain-JDK в `lib/runtime/bin/`
  и генерирует `bin/kasane-compose-java`; `.desktop` и все спавны хелперов идут через
  `java -cp lib/app/*` напрямую — лаунчер не используется, дампов ноль (smoke под Xvfb: 0 новых
  записей в coredumpctl). `exitDesktop` (`C/Exit.kt`) добивает и teardown-дамп при прямом запуске
  ELF; `killTree` — мгновенная остановка хелперов без graceful-выхода. Проверка:
  `C:ProcessKillTreeTest`. Аудит завершений (после вывода лаунчера из цепочки): безусловный
  SIGKILL заменён на `Process.stopTree` (SIGTERM → `killTree` только при зависании) в
  `VerifierProcess` (idle-stop и failure-пути) и `LoginProcess.cancel`; mpv и системные пробы
  уже завершались по схеме «graceful → forcibly по таймауту». Под `java`-запуском `exitDesktop`
  идёт через штатный `exitProcess` (ветка `_exit` — только для прямого запуска ELF).
- ~~**KI-30. `file:`-URI с одним слэшем ломал офлайн-воспроизведение.**~~ Исправлено:
  `OfflineStreamSource` передавал mpv `File.toURI()` (`file:/path`), что mpv отвергает
  (`end-file reason=error file_error="loading failed"`) — сохранённые файлы не играли никогда.
  Теперь передаётся `File.absolutePath`. Регрессию ловит `MpvEngineTest`. Замечание: `file:`-URI
  в `DesktopOffline`/`Artwork` (обложки) корректны — их разбирает `java.net.URI`, не mpv.
  **Ограничение окружения:** верифицированный путь требует GTK-верификатора, а `gtk_init`
  нужен display — `--smoke`/`--connect-smoke` без `DISPLAY` падают на гостевой фолбэк,
  который YouTube обрывает (см. «Внешние ограничения»); под `Xvfb` (`DISPLAY=…`) smoke зелёный.

## Tauri / Rust

- **KI-23. Shuffle не реализован;** команда Connect `shuffle` возвращает ошибку. Repeat доступен
  только через Connect, кнопок в UI нет.
- **KI-24. Progressive-поток скачивается в память целиком** до начала воспроизведения
  (`DesktopAudioBackend::download`).
- **KI-25. Очередь продвигается опросом из UI** (`get_playback_state` → `advance_if_finished`).
  Риск: если WebView перестанет опрашивать, следующий трек не начнётся. Не проверялось.
- **KI-26. CSP отключён** (`"csp": null`).
- **KI-27. `NativeDspAnalyzer` возвращает выдуманные значения** (BPM, тональность из хэша id). Не
  вызывается; нарушает правило «никаких заглушек». Трейты `AIProvider`, `AudioAnalyzer`, таблица
  `track_features`, `MockProvider` — не используются приложением.
- **KI-28. У фронтенда нет тестов** (только `svelte-check`).
- ~~**KI-29. Маркер транзиентного сбоя офлайн-загрузки живёт в памяти.**~~ Исправлено:
  `transientFailures` сохраняется в SharedPreferences (`persistTransient`), очередь поднимает
  транзиентные `failed`-строки после перезапуска процесса. Плюс воркер перепроверяет блокировку
  каждые 15 с (`withTimeoutOrNull` вместо вечного `receive`), поэтому возврат сети не требует
  `NetworkCallback.onAvailable`. Проверено на устройстве: воркер пережил `am force-stop`
  и продолжил очередь сам.

## Внешние ограничения

- Клиент неофициальный: любое изменение YouTube (BotGuard, `base.js`, версии клиентов) ломает
  воспроизведение до обновления `youtubei.js`/`bgutils-js` в `verifier/`.
- Анонимные резервные источники деградировали (живые пробы): клиент iOS больше не отдаёт
  `hlsManifestUrl`, ANDROID_VR для части треков возвращает `LOGIN_REQUIRED`, а где резолвится —
  поток обрывается YouTube примерно через 29 с. Верифицированный WEB_REMIX — фактически
  единственный полноценный анонимный путь; на Android/Tauri это ослабляет их fallback-цепочки.
- Android с включённым Restricted networking: Connect работает только при активном приложении
  (`docs/architecture/multiplatform.md`).
- Лицензия проекта не выбрана (`README.md`).
