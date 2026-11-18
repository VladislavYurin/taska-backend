## Why

[TAS-534](https://jira.ozero.dev/browse/TAS-534): сохранять требования и сценарии новых backend-задач рядом с реализацией без зависимости от истории чата.

## What Changes

- Закрепить CLI и проектные Codex skills официальной версии OpenSpec 2.1.0.
- Добавить контекст Taska и baseline требований к разработке, проверкам и авторизованному merge.
- Проверять active specs/changes и archived tasks в CI раздельно.
- Различать metadata и неизвестные executables в impact planner, сохраняя консервативный fallback.
- Описать отдельную Jira-задачу для нерешённого бизнес-вопроса.

## Capabilities

### New Capabilities

- `development-workflow`: baseline существующего harness и нового OpenSpec процесса. Это tooling change; baseline добавлен непосредственно в specs, поэтому skip_specs включён явно.

### Modified Capabilities

- Нет.

## Impact

Только документация, агентный процесс и CI/harness. REST, RPC, схемы БД и runtime сервисов не меняются. CLI не устанавливается скрыто обычным Maven build. Фактический discovery skills в уже открытом клиенте проверяется отдельно.
