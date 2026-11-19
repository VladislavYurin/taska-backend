# Proposal

## Why

TAS-363 свободна после завершённых TAS-323 и TAS-343. На develop a0499412 есть native issue CRUD/ACL и project_labels, но labels/watchers API нативного профиля закрыты, а legacy-пути не дают tenant-изоляцию.

## What Changes

- Активировать существующие REST/RPC labels/watchers через native хранилище и текущие capabilities/project ACL.
- Добавить tenant/project-qualified связи и атомарные изменения, историю и body-free outbox; повтор не создаёт второй эффект.
- Проверять target membership, project access и активный account перед watch и чтением получателей; удалённые профили не обогащать из старых событий.
- Добавить ограниченные purpose-bound RPC проверки project recipients и разрешения актуальных issue recipients для последующей доставки.
- Сохранить legacy профиль и действующие DTO; общий transport bus и delivery workers остаются задачами TAS-321/325/326, без fallback на legacy recipients.

## Capabilities

### New Capabilities
- `tenant-labels-watchers`: безопасные метки, подписки и актуальная область получателей.

### Modified Capabilities
Нет.

## Impact

issue-service владеет project_labels/issue_labels/issue_watchers/history/outbox; project-service подтверждает project grant, tenant-service — membership, auth-service — ACTIVE account. Proto добавляет закрытые internal RPC; существующий публичный REST не меняет форму. Затрагиваются gateway mapping, gRPC policy, schema, интеграционные тесты и owned E2E. Изменение ещё не реализовано и не развёрнуто; merge/deploy запрещены поручением пользователя.
