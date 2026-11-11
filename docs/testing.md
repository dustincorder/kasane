# Kasane — Testing & Verification Guide

Руководство по тестированию и верификации компонентов Kasane (カсане).

---

## 1. Unit-тестирование Android

Unit-тесты Android расположены в `android/app/src/test/java/cord/kasane/music/`.

### Запуск тестов:
```bash
cd android
./gradlew testDebugUnitTest
```

### Основные тест-сьюты:
- `KasanePlaybackAndSearchTest.kt`:
  - Проверка валидации моделей `Track`.
  - Мок-тестирование логики поиска и извлечения стримов через `MusicProvider`.
  - Тестирование Room DAO (`TrackDao`) и потока недавних воспроизведений.

---

## 2. Тестирование Rust Workspace (Backend & Engine)

Тесты Rust распределены по crates: `vibe-core`, `vibe-audio`, `vibe-db`, `vibe-providers`.

### Запуск тестов:
```bash
cargo test
```

### Сценарии:
- `vibe-audio`: Проверка DSP анализа аудиосигнала (`test_dsp_analysis`).
- `vibe-core`: Проверка сериализации/десериализации моделей треков.
- `vibe-db`: CRUD операции с базой SQLite (избранное, история воспроизведения).
- `vibe-providers`: Мок-резолвинг треков и парсинг InnerTube ответов.

---

## 3. Визуальная верификация Jetpack Compose (Headless)

Для проверки UI без физического Android-устройства используется Android CLI tool.

### 3.1. Список доступных Compose Previews
```bash
cd android
android preview list :app
```

### 3.2. Рендеринг превью в PNG
```bash
cd android
android preview render \
  --fqn=cord.kasane.music.ui.components.TrackRowKt.TrackRowPreview \
  --fqn=cord.kasane.music.ui.components.MiniPlayerKt.MiniPlayerPreview \
  --fqn=cord.kasane.music.ui.KasaneAppKt.HomeScreenPreview \
  --output=build/previews :app
```

Результаты сохраняются в `android/build/previews/` в формате PNG для инспекции типографики, отступов и цветов.
