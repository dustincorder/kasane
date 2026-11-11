# Kasane — Development & Build Guide

Руководство по локальной разработке и сборке Kasane (カсане) для Android и Linux.

---

## 1. Системные требования

- **ОС**: Linux (x86_64 / arm64)
- **JDK**: Java 17+ / 21 (`java -version`)
- **Android SDK**: `compileSdk = 35`, Build Tools `35.0.0`
- **Rust**: `rustc` 1.80+ / Cargo
- **Node.js**: Node 20+, npm / pnpm
- **Android CLI**: Утилита `android` (`android --version`) для рендеринга Compose Previews без эмулятора.

---

## 2. Сборка и запуск Android-клиента

Проект расположен в директории `android/`.

### 2.1. Запуск unit-тестов
```bash
cd android
./gradlew testDebugUnitTest
```

### 2.2. Сборка Debug APK
```bash
cd android
./gradlew assembleDebug
```
Собранный APK сохраняется в:
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 3. Сборка и запуск Linux Desktop (Tauri + Rust + Svelte)

Проект расположен в корневом каталоге.

### 3.1. Установка зависимостей frontend
```bash
npm install
```

### 3.2. Проверка и тесты Rust crates
```bash
cargo test
cargo check
```

### 3.3. Запуск dev-сервера десктопного приложения
```bash
npm run tauri dev
```

### 3.4. Сборка релизного пакета
```bash
npm run tauri build
```

---

## 4. Ограничения разработки и правила

- Не использовать `unwrap()` в продакшен-коде Rust.
- Не использовать моноширинные шрифты в стандартном пользовательском UI.
- Следовать палитре `KasaneRose` (`#EF5C7D`) / `KasaneCrimson` (`#D52F58`).
- Не производить прямые пуши в ветку `main` без согласования.
