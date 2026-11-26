# Разработка и проверка

Команды проверены 2026-11-23 на Linux: JDK 21 (toolchain 17), Gradle 8.9 (wrapper), Rust 1.99,
Node 26, mpv в `PATH`.

## Окружение

- JDK 17+ и Android SDK 35 (`android/local.properties` с `sdk.dir`).
- Rust stable; системные пакеты Tauri для Linux (WebKitGTK 4.1, librsvg, ALSA, OpenSSL).
- Node 20+; `npm ci` в корне.
- `mpv` 0.38+ — только для запуска Compose desktop.
- `verifier/dist/kasane-verifier.js` обязателен для сборки Android и desktop:
  `npm run build:verifier`. Без него `:app:preBuild` падает.

## Проверки (безопасные: без запуска приложений, устройств и сети YouTube)

```sh
scripts/verify.sh            # всё
scripts/verify.sh rust       # cargo test --workspace; cargo clippy --workspace --all-targets -- -D warnings
scripts/verify.sh web        # npm run build:verifier; npm run check
scripts/verify.sh kotlin     # cd android && ./gradlew :shared:jvmTest :desktop:test :app:testDebugUnitTest :app:assembleDebug
```

Какие проверки обязательны при изменении:

| Изменено | Запустить |
|---|---|
| `shared/`, `shared-ui/` | `kotlin` целиком (затронуты оба Kotlin-клиента) |
| `android/app` | `:app:testDebugUnitTest :app:assembleDebug` |
| `desktop-compose/` | `:desktop:test`, желательно `:desktop:run --args="--smoke"` (нужна сеть и mpv) |
| `crates/`, `src-tauri/` | `rust` |
| `src/` | `web` |
| `verifier/` | `web` + `kotlin` + `rust` (бандл встроен во все клиенты) |
| Протокол Connect | `kotlin` + `rust` + interop (ниже) |

Результат аудита 2026-11-23: Rust — 45 тестов, clippy без предупреждений; `svelte-check` — 0 ошибок;
`:shared:jvmTest` — 86; `:desktop:test` — 5; `:app:testDebugUnitTest` — 256, из них 11 пропущено
(opt-in); `:app:assembleDebug` и `:poc-webview:compileKotlin` — успешно. У `:ui` тестов нет.

## Opt-in проверки (ходят в сеть или требуют подготовки; по умолчанию пропускаются)

| Переменная | Что включает |
|---|---|
| `KASANE_RUST_CLI=target/release/kasane-connect-cli` | `ConnectInteropTest`: Kotlin↔Rust по реальному TLS. Сначала `cargo build --release -p kasane-connect` |
| `KASANE_LIVE_PROBES=1` | живые гостевые запросы каталога и воспроизведения |
| `KASANE_GUEST_PLAYBACK_LIVE=1`, `KASANE_GUEST_PLAYBACK_FULL_LIVE=1` | чтение реальных аудио-байтов |
| `KASANE_ARTWORK_LIVE=1`, `KASANE_LIVE_FIXTURE_DIR=<dir>` | обложки; захват фикстур |

Headless-режимы Compose desktop (сеть, mpv без звука): `./gradlew :desktop:run --args="--smoke"`,
`--connect-smoke`, `--render DIR`. Инструментальные тесты Android — только
`./gradlew kasaneApi35DebugAndroidTest` на управляемом эмуляторе.

MPRIS2-проверка: `dbus-run-session -- ./gradlew :desktop:run --args="--mpris [сек] [videoId]"` держит
`org.mpris.MediaPlayer2.kasane` на сессионной шине (`-PkasaneChannel=nightly` → `…kasane.nightly`);
управление через `busctl --user call … org.mpris.MediaPlayer2.Player PlayPause`. Библиотека —
`dbus-java` 5.1.1 (core + native-unixsocket; Unix-сокеты через уже имеющийся JNA), MIT-style
лицензии, ~600 КБ.

## Запуск и сборка приложений (вручную, не из автоматических проверок)

```sh
cd android && ./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk (id …music.dev)
cd android && ./gradlew :desktop:run              # Compose desktop
cd android && ./gradlew :desktop:createDistributable
cd android && ./gradlew :desktop:createDistributable -PkasaneChannel=nightly   # nightly-вариант
```

### Канал сборки desktop (`-PkasaneChannel=`)

`stable` (по умолчанию) и `nightly` — полностью параллельные установки: канал запекается в
`kasane-build.properties` (задача `generateBuildInfo`), `BuildInfo` раскладывает все persisted-
имена по каналу: XDG-каталог `cord.kasane.compose[.nightly]`, схема Secret Service
`cord.kasane.compose[.nightly].session`, имя приложения «Kasane [Nightly]», пакет/AppDir
`kasane-compose|kasane-nightly`. Nightly-иконка — слот `assets/logo-nightly.png` (даёт
@designer); пока файла нет — используется stable-иконка.

OTA (`S/ota/`): `UpdateChecker` ходит в GitHub Releases `dustincorder/kasane`; stable берёт
только `prerelease=false`, nightly — только `prerelease=true`. Автопроверка раз в сутки
(`lastUpdateCheckMs` в настройках), ручная — из Settings → About. Выбор ассета — матчеры
`BuildInfo.assetMatchers` (stable исключает имя с `nightly`); SHA256 — из asset `digest` или
`<name>.sha256`/`SHA256SUMS`. Применение — `install.sh` из распакованного tar.gz по явному
действию пользователя, без самозамены. Релизный пайплайн обязан: nightly-тарбалл класть как
pre-release со своим `install.sh` (каталог `~/.local/opt/kasane-nightly`, desktop-запись
`cord.kasane.desktop.nightly.desktop`) и публиковать checksum (`digest` идёт автоматически).

Tauri/Rust-клиент заморожен (решение D1): сборка только для справки и Connect-interop, не
релизный путь — `npx tauri dev`, `npx tauri build --no-bundle` → `target/release/kasane`.
`cargo build --release -p kasane` даёт бинарник без `custom-protocol`: он ищет dev-сервер и
показывает «Connection refused». Release APK подписывается ключами из `~/.glazegram/`.

## Запрещено

- Любые `connected*AndroidTest` (заблокированы в Gradle), `adb uninstall`, `pm clear`.
- Установка на телефон — только `scripts/android-safe-install.sh <serial> <apk>` и только по просьбе
  владельца. Подробности: `docs/incidents/android-test-data-loss.md`.
- Автотесты, которые меняют данные реального аккаунта YouTube.

## Предлагаемые автоматические проверки (не реализованы)

Упорядочены по отношению пользы к стоимости.

1. **CI на push/PR** с тем же набором, что `scripts/verify.sh`, включая Gradle. Сейчас CI ручной и
   без Kotlin (KI-5).
2. **Kotlin↔Rust interop в CI:** собрать `kasane-connect-cli` и запускать `ConnectInteropTest` с
   `KASANE_RUST_CLI` (KI-11).
3. **Контрактный тест плеера:** один набор сценариев (новый контекст, play next, удаление текущего,
   shuffle, repeat, конец очереди, радио) против `DesktopPlayer` и против Android-пути на фейковом
   движке. Ловит расхождения KI-8, KI-10 и требует общего интерфейса `PlayerController`.
4. **Тесты миграций Room:** включить `exportSchema`, добавить `MigrationTestHelper` 1→7 (KI-12).
5. **Общие фикстуры InnerTube для Kotlin и Rust:** прогонять `shared/src/jvmTest/resources/captured/*`
   через Rust-парсеры, пока Rust существует.
6. **Проверка матрицы:** скрипт, который падает, если путь из `FEATURE_MATRIX.md` не существует.
7. **Снимки экранов:** `--render` Compose desktop и Robolectric-снимки Android с попиксельным
   сравнением для общих компонентов `:ui`.
8. **Ночной живой прогон** (`KASANE_LIVE_PROBES=1`, `--smoke`) отдельно от основного CI: раннее
   обнаружение поломок со стороны YouTube.
