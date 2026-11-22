# Android design system (current)

Material 3, dark charcoal, one cold-blue accent. Source of truth: `android/app/.../ui/theme/Theme.kt`.

| Role | Value | Use |
|---|---|---|
| Background / surface | `#0F1012` | App base |
| Surface container low/default/high | `#151619` / `#181A1E` / `#1B1D21` | Sheets, navigation bar, mini-player |
| Primary (`KasaneBlue`) | `#8AB6F9` | Active track, progress, play button, selected states |
| Primary container | `#1C2E47` | Selected chips and navigation indicator |
| On-surface / variant | `#D9DCE2` / `#A6ACB6` | Text, metadata |
| Outline / variant | `#3A3E45` / `#26292E` | Dividers and borders |

Rules: use `MaterialTheme.colorScheme` roles (never raw constants) so optional Material You accents work; shapes 4/8/12/16/24 dp, content 8 dp, player/sheets larger; sans-serif only; overflow buttons on artwork keep a 48 dp touch target with a 26 dp scrim chip and 16 dp glyph; no purple gradients, glows or oversized cards. Dynamic color only recolours accent roles; surfaces stay charcoal for stable contrast.

The sections below describe the legacy Rose palette still used by the Linux desktop until it is redesigned.

# Kasane — Design System

Дизайн-система музыкального плеера **Kasane (カサネ)**.

---

## 1. Цветовая палитра (Kasane Brand Identity)

Фирменная палитра Kasane основана на официальном логотипе (спираль) и сочетает глубокие тёмные тона с насыщенными красно-розовыми акцентами:

| Название токена | Hex | Применение |
|---|---|---|
| **Kasane Rose** (`accent-highlight`) | `#EF5C7D` | Фокусированные иконки, индикаторы прогресса, активные табы, выделенный текст |
| **Kasane Crimson** (`accent-active`) | `#D52F58` | Первичные кнопки действия (Play/Pause FAB), бейджи, акцентные обводки |
| **Kasane Wine** | `#790F32` | Градиенты, тени, второстепенные акценты, состояния Pressed |
| **Kasane Wine Subtle** (`accent-subtle`) | `#2B121B` | Фоновые плашки активных элементов, контейнеры чипов, подложка мини-плеера |
| **Background Dark** | `#0C0D10` | Основной фон приложения |
| **Surface Dark** | `#14151B` | Карточки треков, выпадающие панели, нижняя панель навигации |
| **Text Primary** | `#FFFFFF` | Названия треков, основные заголовки |
| **Text Secondary** | `#9E9E9E` | Имена исполнителей, длительность, второстепенные метки |

### Контрастность и доступность (WCAG)
- Контраст `#EF5C7D` на фоне `#0C0D10` составляет **6.01:1**, что полностью удовлетворяет стандарту **WCAG AA** для обычного текста и графических элементов интерфейса.

---

## 2. Типографика

- Основное семейство шрифтов: **Sans-Serif** (Inter / Roboto / System Sans-Serif).
- **Правило**: Запрещено случайное использование `Monospace` для названий треков, исполнителей и временных меток. Моноширинный шрифт допустим исключительно для технической отладки.

| Стиль | Размер / Вес | Назначение |
|---|---|---|
| `titleLarge` | 22sp / SemiBold | Названия альбомов, заголовки разделов Home |
| `titleMedium` | 18sp / Medium | Заголовки экранов и модальных панелей |
| `bodyLarge` | 16sp / Normal | Названия треков в списках |
| `bodyMedium` | 14sp / Normal | Имена исполнителей, описания |
| `labelMedium` | 12sp / Medium (Sans-Serif) | Временные метки (0:00 / 3:15), чипы категорий |
| `labelSmall` | 11sp / Normal (Sans-Serif) | Вторичные метаданные, битрейт, формат |

---

## 3. Компоненты UI

### 3.1. Строка трека (`TrackRow`)
- Высота: 56dp.
- Обложка: скругление 6dp (или круг для исполнителей).
- Активное состояние: название окрашивается в `KasaneRose` (`#EF5C7D`), рядом отображается анимированный индикатор воспроизведения.

### 3.2. Мини-плеер (`MiniPlayer`)
- Расположение: плавающий блок над нижней навигацией.
- Форма: закругленный прямоугольник (12dp), с тонкой акцентной линией прогресса `KasaneRose` в верхней кромке.
- Контролы: Play/Pause, кнопка закрытия или перехода к следующему треку.

### 3.3. Экран текущего трека (`NowPlayingSheet`)
- Разворачивается снизу (BottomSheet).
- Крупная обложка альбома с мягкой тенью цвета `KasaneWine`.
- Скруббер с ползунком `KasaneRose`.
