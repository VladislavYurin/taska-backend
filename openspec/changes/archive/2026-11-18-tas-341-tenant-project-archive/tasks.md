## 1. Контракты и реализация

- [x] 1.1 Проверить TAS-341, зависимости, текущий native/legacy код и зафиксировать acceptance.
- [x] 1.2 Добавить аддитивные proto/REST поля, restore маршрут и native lifecycle с current ACL, CAS, idempotency/outbox/audit.
- [x] 1.3 Добавить новую миграцию квитанций с RLS и append-only защитой, сохранив предыдущие changesets.

## 2. Проверки

- [x] 2.1 Проверить реальный PostgreSQL: успех, отказ, A/B, stale/concurrency, retry/conflict, rollback, quota/история и последний ADMIN.
- [x] 2.2 Проверить gateway contract/headers и применимые manifest/IT; выполнить локальный REST/mTLS E2E на своих данных.
- [x] 2.3 Синхронизировать документацию и strict specs; выполнить review и затронутые повторные проверки.

## Workflow follow-up

Архивировать завершённый change, проверить CI/AI review текущего PR head, повторить затронутый E2E и выполнить авторизованный merge.
