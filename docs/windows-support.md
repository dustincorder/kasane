# Windows Support Audit — Compose Desktop (0.2.1-alpha)

Аудит `desktop-compose/**` + `shared/**` на предмет Windows-сборки. CI (`release.yml`, job
`windows` на `windows-latest`) уже пакует portable ZIP через `:desktop:createDistributable` +
`Compress-Archive`. Этот документ — что реально работает в таком артефакте, что сломано и что
требует портирования. Не проверено на живой Windows — выводы по коду.

## Блокер сборки артефакта (не рантайма)

**`createDistributable` падает на Windows в `doLast`-хуке** (`desktop-compose/build.gradle.kts`).
Хук копирует `File(packagingJdk, "bin/java")` → `lib/runtime/bin/java` и пишет sh-скрипт
`bin/<pkg>-java` (обход краша jpackage ELF-лаунчера на Linux). На Windows JDK файл —
`bin/java.exe`, `bin/java` не существует → `copyTo` кидает `NoSuchFileException` →
`createDistributable` BUILD FAILED → windows-job в CI умирает до упаковки ZIP. Лечение (не
сделано — задача аудит): ветвление по `OperatingSystem.current().isWindows` — копировать
`bin/java.exe` (нужен `--verifier`/`--login` хелперам, у них fallback на лаунчер) и писать
`.cmd`-шим вместо sh, либо пропускать хук на Windows (краш лаунчера — glibc-специфичен, `.exe`
предположительно исправен).

Менее критично: `targetFormats(TargetFormat.AppImage)` — формат Linux-only; на Windows
валидация, вероятно, ленивая (AppImage запрашивается только `packageAppImage`, который CI не
зовёт) — но не проверено.

## Матрица по подсистемам

| Подсистема | Статус | Что происходит на Windows |
|---|---|---|
| **Компиляция `:desktop`** | сломано | `createDistributable` → doLast crash (см. выше). Сами исходники компилируются — Unix-API (`UnixDomainSocketAddress`, JNA-имена) легальны на Windows JDK |
| **Запуск приложения** | **сломано — dead on launch** | `DesktopApp` eager-конструирует `MpvEngine` (`init` спавнит процесс): без `mpv.exe` на PATH → `IOException("Kasane needs mpv")`; с mpv.exe → IPC ниже. `App.kt` не оборачивает `DesktopApp()` — исключение убивает окно до показа |
| **mpv IPC** | сломано | `--input-ipc-server=<tempdir>/ipc.sock` — mpv на Windows открывает именованный пайп `\\.\pipe\<name>`, путь со слэшами — невалидное имя; `SocketChannel`/`UnixDomainSocketAddress` к named pipe не подключаются. Нужен named-pipe IPC (`RandomAccessFile`/`\\.\pipe\`) или иной бэкенд |
| **StreamProxy** | работает | чистый `ServerSocket` на loopback, портабельно |
| **Авторизация (sign-in)** | сломано — логин невозможен | `LoginProcess` → хелпер `--login` → `LoginWindow` — JNA на `gtk-3`/`webkit2gtk-4.1`/`soup-3.0` → `UnsatisfiedLinkError` → "sign-in window could not start". Фолбэка нет (нужен браузер с захватом cookies — на Windows это JCEF/WebView2, не системный браузер) |
| **Хранилище сессии** | деградирует корректно | `SecretServiceStore` — JNA `secret-1` (libsecret) → `runCatching` в `DesktopApp` → memory-only сессия, UI помечает. На Windows нужна Credential Manager/DPAPI-обёртка за `SessionStore` |
| **Verifier (BotGuard)** | сломано — деградация воспроизведения | `WebKitGtkRuntime`/`VerifierProcess` — JNA webkit2gtk. Хелпер `--verifier` сам по себе запустится (spawn-логика портабельна: `java.home/bin/java(.exe)` → fallback на `ProcessHandle.command`), но `getInstance("webkit2gtk-4.1")` упадёт → `ready:false` → `UNAVAILABLE` → `StreamSelector` падает в ios-hls → guest — частичное/обрезанное воспроизведение (~29 с гостевых потоков) |
| **Воспроизведение (цепочка)** | условно | `StreamSelector` грейсфул: verified → ios-hls → guest. Сломан движок (mpv IPC), а не резолв |
| **MPRIS (медиаклавиши)** | деградирует, edge-риск | `MprisServer.start()` — сессионной шины на Windows нет → `forSessionBus().build()` бросает → `catch (Exception)` → `false`, приложение живёт. Edge: если `jnr-unixsocket`/JNA кинет `Error` (не `Exception`) — уйдёт вверх и убьёт запуск; стоит `runCatching`/catch Throwable. **SMTC отсутствует** — медиаклавиши/наушники на Windows не работают в принципе |
| **Пути данных** | работает неканонично | `dataDir = XDG_DATA_HOME ?: ~/.local/share` → `C:\Users\<u>\.local\share\cord.kasane.compose` — создастся и будет работать, но не `%APPDATA%`. Nightly: `cord.kasane.compose.nightly` — разделение сохраняется автоматически |
| **install.sh / .desktop** | неприменимо | sh-инсталлер Linux-only; Windows-запуск = `bin\kasane-compose.exe` из ZIP. Start Menu-ярлыка/ассоциации `kasane:`-URI нет (нужен MSI/EXE или регистрация через реестр) |
| **OTA-обновления** | безопасно молчит | `BuildInfo.assetMatchers` захардкожены `linux/x86_64/.tar.gz` → на Windows подходящего ассета нет → апдейт не предлагается (fail-safe, не баг). Для поддержки — windows-матчеры + распаковка ZIP (нет `tar`/`install.sh`) |
| **SystemScale** | деградирует корректно | `xrdb`/`kscreen-doctor` — `runCatching` → 1.0 |
| **Connect (JmDNS + TLS)** | вероятно работает | чистый Java mDNS и TLS; самоподписанные серты платформонезависимы |
| **Exit/stopTree** | работает | `Process.descendants`/`destroy` — портабельны; `_exit` через JNA `c` → msvcrt имеет `_exit`, плюс `halt` fallback |
| **DBus-зависимости вообще** | ок | dbus-java на classpath безвредна — используется только MPRIS (ленивый `start()`) |

## Что требует портирования (по приоритету)

1. **Блокер**: OS-aware `doLast` в `createDistributable` (иначе артефакт не собирается).
2. **Запуск**: не eager `MpvEngine` — guard по `os.name` + отложенная инициализация, либо второй
   `AudioEngine` (mpv по named-pipe `\\.\pipe\kasane-mpv-*` — mpv.exe для Windows существует —
   или Windows-бэкенд). Без этого приложение не открывает окно.
3. **Воспроизведение на уровень Linux**: замена WebKitGTK-верификатора на Windows-движок
   (JCEF/WebView2-хелпер за тем же `--verifier` JSON-протоколом — протокол уже изолирован).
4. **Логин**: Windows-окно входа с захватом cookies (тот же JCEF/WebView2) — сейчас гостевой
   режим единственный.
5. **Сессия**: `SessionStore` поверх Windows Credential Manager (DPAPI).
6. **MPRIS→SMTC**: System Media Transport Controls через WinRT/bridge — отдельный сервис за
   общим интерфейсом; расширить `start()` до catch Throwable.
7. **Пути**: `%APPDATA%`/`%LOCALAPPDATA%` ветка `dataDir`.
8. **Упаковка**: `packageMsi`/`packageExe` (WiX на раннере) или ZIP + `.cmd`/`exe` шим; OTA —
   windows-матчеры ассетов + ZIP-применение.
