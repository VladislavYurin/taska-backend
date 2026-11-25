# Tasks

## 1. Deadline и состояние

- [x] 1.1 Добавить детерминированную PG/mTLS регрессию policy/billing/owner timeout: до исправления получить сырой TimeoutException, после — управляемый отказ; проверить rollback и сохранённый intent/reservation.
- [x] 1.2 Преобразовать только TimeoutException локальных storage dependency deadlines в UNAVAILABLE и trusted upload-url dependency errors в Problem/503; проверить успех, отказы, replay/concurrent confirm и единственный эффект учёта.
- [x] 1.3 Обновить upload runbook и выполнить docs-check, подтверждая отсутствие новых полей REST/proto, прежние TTL и инструкцию retry исходного ключа.

## 2. Сквозная проверка и ревью

- [x] 2.1 Выполнить выбранные tenant-service/gateway/storage проверки, harness и strict OpenSpec validation; сверить свежие отчёты без skips.
- [x] 2.2 Подготовить отдельный PR, разобрать current-head CI/AI review и повторить owned native timeout/upload E2E на финальном head после review; подтвердить cleanup.

## Workflow follow-up

- Синхронизировать спецификацию, архивировать завершённый change и проверить архив перед merge.
- Merge в develop запускает deployment; требуется явное разрешение пользователя на конкретный готовый PR.
