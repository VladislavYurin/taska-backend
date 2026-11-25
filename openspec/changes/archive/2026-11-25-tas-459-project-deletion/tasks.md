# Tasks

## 1. Контракты и принятие
- [x] 1.1 Добавить operation/proto/REST и сохранить существующую wire version проекта; проверить контрактные тесты и документацию.
- [x] 1.2 Реализовать project intent/fence/ACL/version/idempotency с PostgreSQL rollback/replay/concurrency tests и описанием отказов.

## 2. Очистка владельцев
- [x] 2.1 Реализовать issue/storage cleanup, lifetime retire и защиту поздних записей; проверить PostgreSQL/S3 отказ, retry, tenant/avatar isolation и сохранить runbook.
- [x] 2.2 Реализовать workflow cleanup только project-owned данных; проверить сохранение templates/других проектов и повтор.
- [x] 2.3 Связать owner DELETED proof, однократный PROJECT release и durable recovery; проверить crash/lost ACK/restart и документацию activation.

## 3. Проверки передачи
- [x] 3.1 Пройти harness plan/check, явные IT, docs/OpenSpec validation и owned E2E полного lifecycle.
- [x] 3.2 Создать русский PR, пройти CI и актуальное AI review, разобрать замечания и повторить затронутый E2E на final head.

## Workflow follow-up
- Синхронизировать specs и архивировать только после завершения всех пунктов; ничего не вливать.
