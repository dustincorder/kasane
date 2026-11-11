---
name: kasane-verify
description: Обязательная комплексная верификация перед сдачей любой задачи в Kasane. Проверяет компиляцию, unit-тесты (Kotlin/Android & Rust/Linux), рендеринг Compose Previews и формирует структурированный отчёт с уровнями доказательств (что проверено, что прошло, риски регрессий).
---

# Kasane Verification Protocol

Протокол доказательной проверки изменений перед отчётом пользователю.
**Правило**: `BUILD SUCCESSFUL` не означает, что приложение работает. Требуется проверка уровней доказательств.

## Уровни доказательств (Proof Levels)

| Уровень | Что проверяет | Команда | Статус |
|---|---|---|---|
| **L1: Компиляция** | Отсутствие синтаксических ошибок типов | Android: `./gradlew assembleDebug`<br>Linux: `cargo check` | Обязательно |
| **L2: Unit-тесты** | Корректность бизнес-логики, парсинга, DAO | Android: `./gradlew testDebugUnitTest`<br>Linux: `cargo test` | Обязательно |
| **L3: Headless Previews** | Рендеринг Compose UI без физического девайса | `android preview render ... :app` | Обязательно для UI задач |
| **L4: UI/Интеграция** | Интеграция сервисов и базы | Сквозные сценарии компонентов | По мере готовности |
| **L5: Устройство** | Проверка на реальном Android девайсе | `adb install` / ручной запуск | Только по прямому запросу |

## Обязательный чеклист перед отчётом

1. **Запуск тестов Android**:
   ```bash
   cd android && ./gradlew testDebugUnitTest
   ```
2. **Сборка APK Android**:
   ```bash
   cd android && ./gradlew assembleDebug
   ```
3. **Проверка Rust Workspace (Linux Desktop)**:
   ```bash
   cargo test && cargo check
   ```
4. **Рендеринг Compose Previews (при правках интерфейса)**:
   ```bash
   cd android && android preview render \
     --fqn=cord.kasane.music.ui.components.TrackRowKt.TrackRowPreview \
     --fqn=cord.kasane.music.ui.components.MiniPlayerKt.MiniPlayerPreview \
     --fqn=cord.kasane.music.ui.KasaneAppKt.HomeScreenPreview \
     --output=build/previews :app
   ```

## Формат отчёта агента

Каждый агент (Codex или Antigravity) в конце отчёта обязан включить секцию верификации:
```markdown
### Верификация изменений
- **Что изменилось**: [краткий список изменений]
- **Затронутые файлы**: [список файлов]
- **Запущенные тесты**: [список команд и тест-сьютов]
- **Результаты проверок**: [пройденные уровни L1-L3]
- **Что НЕ проверено**: [непроверенные граничные условия или устройства]
- **Возможные риски / регрессии**: [на что обратить внимание]
```
