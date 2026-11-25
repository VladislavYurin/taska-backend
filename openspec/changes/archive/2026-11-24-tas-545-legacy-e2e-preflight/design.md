## Context

В Project OutboxEventServiceImpl.save требуется VerifiedTenantContext. Mapper и publisher требуют tenant envelope v2 и tenant partition key; issue outbox также строгий. Local E2E-08 provision использует legacy Compose без trusted RPC. Совместимость нельзя восстановить одним nullable tenant.

## Goals / Non-Goals

Goals: предусмотренный TAS-545 BLOCKED до записи; независимый read-only E2E-01; неизменный stage и native контур; отчёт с нулём записей и успешным cleanup.
Non-Goals: восстановление событий v1, изменение domain/API/schema/ACL, активация или записи в общей среде.

## Decisions

В runner вводится явная таблица ограничений его local fixture. Подбор runnable происходит до Local.provision; известное ограничение E2E-08 получает собственный scenario/check result с причиной и ссылкой на native runner. Неизвестные сценарии остаются manual; итоговый приоритет BLOCKED над PARTIAL сохраняется. Stage использует прежний pipeline.

Проверки: orchestration regression с запретом provision/write; mixed subset и stage dispatch; реальные local CLI E2E-08 и E2E-01; отдельный owned project_acl_e2e.py. Evidence не входит в Git.

## Risks / Trade-offs

Полная legacy запись этим change не восстанавливается; исправляется обещанный Jira preflight, и это явно отражается в документации/PR. При будущем восстановлении fixture ограничение снимается вместе с положительной проверкой outbox/Kafka. Production guards и потребители событий не затронуты, миграции и rollout данных не требуются.

## Native проверка

Для синхронной компенсации recovery worklist собственной fixture оставлен пустым: конкурирующий worker относится к отдельному сценарию и не должен менять причину отказа между Ensure и ответом HTTP.

Для точных критериев TAS-545 добавляется `project_outbox_e2e.py` на существующей `IssueAclLocal` fixture с последовательным запуском приложений и актуальными trust edges. Проверяются создание в A/B, tenant provenance, один outbox event, replay/conflict, отказ MEMBER с сохранностью состояния при native создании, а также компенсация bootstrap без проекта/занятой квоты/успешного ProjectCreated. Исторический ProjectProvisioned сохраняется, поскольку provisioning уже был закоммичен; проверяется cancellation tombstone. HTTP отказа bootstrap соответствует RestErrorMapper. Полный project ACL runner и согласование его fixture/ожиданий относятся к TAS-546; новый runner не заявляет его полный scope и доставку Kafka.
