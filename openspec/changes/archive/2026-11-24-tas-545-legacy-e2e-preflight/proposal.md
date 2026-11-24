## Why

TAS-545: локальный E2E-08 вызывает legacy создание проекта без verified tenant и получает 500 в outbox. В текущем develop envelope v2 и partition key требуют tenant; снятие проверки переносит сбой в publisher и нарушает границы данных.

## What Changes

- Использовать предусмотренный Jira вариант: несовместимый local legacy write-сценарий получает BLOCKED до provision и любых HTTP-записей.
- Сохранить независимый local E2E-01, opt-in stage и отдельную native project проверку.
- Проверить отчёт, exit code, отсутствие pending writes/ресурсов и cleanup; документировать границу legacy fixture.
- Восстановление полной legacy записи и событий v1 не входит в изменение. REST/proto/миграции и production guards не меняются; доступность shared stage этим change не подтверждается.

## Capabilities

### New Capabilities
- `e2e-capability-preflight`: безопасный отказ несовместимых локальных сценариев до побочных действий.

### Modified Capabilities
Нет.

## Impact

`scripts/harness/e2e.py`, его regression tests, отдельный native project/outbox runner и документация E2E. Базовый снимок — актуальный origin/develop; развёрнутое поведение отдельно не проверяется. Native project ACL/RLS и tenant envelope остаются строгими.
