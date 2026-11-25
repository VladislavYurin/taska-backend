# Design

## Context
См. proposal. ProjectQuotaOwner.recordDeleted требует отсутствия business row, ProjectQuotaStore.releaseUsage — primary DELETED receipt. LifetimeTaskQuota.retire сохраняет counter; AttachmentLifecycle хранит deletion jobs и immutable версии S3. Прямой hard-delete сейчас закрыт SQL guard. Project lifecycle journals и storage receipts содержат FK, которые требуется согласовать с retained deletion proof.

## Goals / Non-Goals
Цель — подтверждаемая возобновляемая сага с локальными транзакциями владельцев. Нет сети под SQL lock, shared-stage очистки, обхода текущих ролей или повторного использования старого DELETE endpoint.

## Decisions
- project-service сохраняет immutable intent, actor/grant authority, project/version/key и этапы cleanup. Idempotency привязана к actor и credential authority. Принятую операцию продолжает свежий SERVICE только по primary persisted proof.
- Отдельные локальные fences в issue/workflow сериализуют in-flight записи и очистку. Primary project API скрывает удаляемый ресурс от новых content calls; operation read остаётся отдельным ACL-protected маршрутом.
- issue-service строит bounded manifest по своему project scope, ждёт исходные PUT windows, удаляет только pinned owned objects и освобождает storage по подтверждённым receipts. Lifetime retire и удаление business data атомарны. Retained quota/audit/idempotency journals не уничтожаются ради FK.
- workflow-service удаляет только project bindings/owned definitions, сохраняет tenant templates и terminal receipts. project-service удаляет собственные business rows после подтверждения обоих владельцев, сохраняет owner DELETED receipt и затем запрашивает release PROJECT usage.
- Каждый remote шаг идемпотентен и сверяет tenant/project/deletion operation с primary project-service. Неизвестный ответ повторяется, не считается успехом. Результат operation содержит bounded failure code без exception text/secret URL.

## Risks / Trade-offs
- Поздняя запись → локальный fence и транзакционный lock владельца; отсутствие proof закрывает доступ.
- Недоступность S3 или сервиса → durable pending, квота удерживается до подтверждения.
- FK retained journals → аддитивные constraint/guard изменения с тестом истории и повторов; применённые changesets не редактируются.

## Migration Plan
Сначала owner migrations/RPC issue/workflow/root, затем project coordinator и gateway. Activation требует managed trust edges и bounded tenant worklist. Не включать на общей среде в рамках этого PR. Незавершённая операция требует совместимой версии продолжения, не отката данных.
