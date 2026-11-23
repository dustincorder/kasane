# Kasane — Linux & Desktop Architecture

Документация десктопного клиента Kasane для Linux (`cord.kasane.desktop`).

---

## 1. Обзор стека

- **Фреймворк**: Tauri v2
- **UI Frontend**: Svelte 5 (Runes), TypeScript, Tailwind CSS, Vite
- **Backend / Core**: Rust 2021 (Workspace crates)
- **База данных**: SQLite через `sqlx` (`kasane-db`)
- **Аудиодвижок**: `rodio` / `cpal` (`kasane-audio`) с поддержкой DSP анализа

---

## 2. Структура Rust Workspace

Кодовая база Rust разделена на модульные crates:

```text
crates/
├── kasane-core/         # Доменные структуры (Track, Artist, Album, Playlist, SearchResult)
├── kasane-audio/        # Движок воспроизведения, буферизация, DSP анализ
├── kasane-db/           # SQLite хранилище избранного, плейлистов и истории
└── kasane-providers/    # Модули интеграции с провайдерами музыки (YouTube Music / InnerTube)
src-tauri/             # Оболочка Tauri 2, IPC команды и привязка оконного интерфейса
```

### Принципы архитектуры Rust
1. **Zero `unwrap()` на путях рантайма**: Все ошибки обрабатываются через `Result` и явные типы ошибок (`thiserror`).
2. **Изоляция провайдеров**: Логика парсинга внешних API изолирована в `kasane-providers`.
3. **Локальность данных**: База данных SQLite обеспечивает мгновенную загрузку истории и кэша без зависимости от сети.

---

## 3. Frontend-архитектура (Svelte 5 & Tailwind)

- Расположение: `src/`
- Стилистика: Тёмная минималистичная тема Kasane с акцентами цвета вина и розы:
  - Фоновый цвет: глубокий тёмный `#0C0D10`
  - Акцент выделения: `#EF5C7D` (`accent-highlight`)
  - Активный интерактивный акцент: `#D52F58` (`accent-active`)
  - Фоновые подложки: `#2B121B` (`accent-subtle`)
- Взаимодействие с Rust происходит через Tauri IPC (`@tauri-apps/api/core` `invoke`).
