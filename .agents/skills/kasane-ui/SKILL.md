---
name: kasane-ui
description: Разработка, редизайн и визуальная валидация интерфейса Kasane в соответствии с дизайн-системой (палитра Kasane Rose/Crimson, Sans-Serif типографика) с обязательным headless-рендерингом Compose Previews через Android CLI. Используй этот навык при изменении любых экранов, карточек, плееров или навигации.
---

# Kasane UI Development & Visual Verification Protocol

Протокол разработки и валидации UI компонентов Kasane для Android (Jetpack Compose) и Linux (Svelte 5 + Tailwind).

## Требования к дизайну

1. **Цветовая палитра**:
   - Фон: Тёмный оникс `#0C0D10`, поверхности `#14151B`.
   - Акценты:
     - `KasaneRose` (`#EF5C7D`) — подсветка активного трека, прогресс-бар, фокусированные иконки.
     - `KasaneCrimson` (`#D52F58`) — основные кнопки действий (Play/Pause FAB).
     - `KasaneWine` (`#790F32`) и `KasaneWineSubtle` (`#2B121B`) — подложки чипов и мягкие градиенты.
   - Запрещены: произвольные неоновые градиенты, сине-фиолетовые цвета старого шаблона.

2. **Типографика**:
   - Строго **Sans-Serif** (`FontFamily.SansSerif` в Compose, стандартный sans-serif шрифт в веб).
   - Запрещено случайное использование `Monospace` для названий треков, исполнителей и меток времени.

## Пошаговый цикл разработки UI

1. **Определи целевой компонент**:
   - `TrackRow.kt`: строка трека в плейлисте / поиске / недавних.
   - `MiniPlayer.kt`: компактный плавающий плеер внизу экрана.
   - `NowPlayingSheet.kt`: развёрнутый экран воспроизведения (BottomSheet).
   - `KasaneApp.kt`: структура главного экрана (Home, TopBar, Quick Picks).

2. **Проверь текущее состояние (Baseline)**:
   - Изучи существующие превью в `android/build/previews/` или срендери их:
     ```bash
     cd android
     android preview render --fqn=cord.kasane.music.ui.components.TrackRowKt.TrackRowPreview --output=build/previews :app
     ```

3. **Внеси точечные изменения в код Compose**:
   - Используй `@Composable` превью с фиктивными данными (не должны попадать в продакшен рантайм).
   - Не добавляй декоративные элементы без функционального смысла.

4. **Отрендери обновлённые Previews**:
   ```bash
   cd android
   android preview render \
     --fqn=cord.kasane.music.ui.components.TrackRowKt.TrackRowPreview \
     --fqn=cord.kasane.music.ui.components.MiniPlayerKt.MiniPlayerPreview \
     --fqn=cord.kasane.music.ui.KasaneAppKt.HomeScreenPreview \
     --output=build/previews :app
   ```

5. **Визуально проинспектируй полученные PNG**:
   - Проверь сгенерированные файлы в `android/build/previews/...`.
   - Убедись в корректности иерархии отступов, контрасте текста и отсутствии наложений элементов.
   - Никогда не оценивай качество визуального интерфейса исключительно по коду без просмотра PNG.
