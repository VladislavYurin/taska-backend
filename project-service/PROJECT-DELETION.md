# Безвозвратное удаление проекта — TAS-459

## Публичный контракт

`POST /api/v1/tenants/{tenantId}/projects/{projectId}/deletion` принимает
`{"confirmed":true,"confirmedProjectKey":"WEB"}`, один канонический UUID
`Idempotency-Key` и сильный `If-Match`, например `"0"`. Версия берётся из
`ProjectResponseDto.version`. Прежний `DELETE /api/v1/projects/{id}` остаётся
архивированием и не освобождает квоту.

Текущие principal, membership generation, project ACL и `project.delete`
проверяются до приёма. Общий evaluator сохраняет B07: в финансовом READ_ONLY
очистка доступна OWNER/TENANT_ADMIN; PROJECT_ADMIN не получает этот обход.
Архивный проект также можно удалить. GLOBAL_ADMIN использует текущую web MFA
и явную tenant administrative scope; глобальная роль пользовательского PAT
не заменяет разрешение.

Ответ `202` содержит `Operation` типа `PROJECT_DELETE`, `resourceId` и tenant.
`GET /api/v1/tenants/{tenantId}/operations/{operationId}` публикует состояние
операций удаления проектов для исходного actor с текущим доступом либо
администратора компании. Остальные виды операций этим маршрутом пока не
публикуются. Контракт ошибок — Problem: 400 validation, 403 ACL/capability,
скрытый 404, 409 другой ключ/конфликт payload, 412 stale version, 422 неверное
подтверждение, 428 отсутствующий If-Match, 503 недоступная authority.

Принятая операция закрывает новые content reads и mutations проекта.
Тот же actor-bound ключ возвращает тот же receipt; новый ключ получает
PROJECT_DELETING. После физического удаления replay сохраняет исходный результат.

## Порядок и восстановление

Project owner сохраняет intent и ProjectDeletionAccepted в одной транзакции.
Managed SERVICE продолжает только существующий intent; новые ресурсы такой
контекст не создаёт. Issue и workflow сверяют tenant/project/operation с primary
ProjectDeletionService перед локальными эффектами.

1. Issue owner фиксирует локальный fence. Bounded manifest охватывает upload
   intents и только project-owned объекты. Завершение/отмена старого upload
   использует исходный receipt. Отдельный append-only cleanup ACK появляется лишь
   после подтверждённого завершения/отмены: одно EXPIRED не считается ACK.
   Confirmed objects проходят обычный durable
   attachment deletion protocol. Сохраняется настоящее PUT window до 5 минут.
2. Пока исход S3/root неизвестен, committed bytes и PROJECT usage удерживаются.
   После terminal object proof и RELEASE_USAGE tasks/history/metadata удаляются;
   lifetime counter становится вечным tombstone. Строки задач удаляются порциями
   по 100; retirement атомарен с завершением последней порции. Чужие tenant/project и avatar namespace
   не входят в manifest.
3. Workflow owner сохраняет локальный fence отдельной транзакцией, удаляет
   bindings и только definitions с данным owner_project_id. Tenant templates и
   immutable instantiation receipts сохраняются.
4. Только после подтверждения обоих владельцев project owner удаляет свои
   business rows, записывает ProjectDeleted и переводит original creation receipt
   в DELETED в той же SQL-транзакции. Исторические identity/operation/audit/outbox
   записи сохраняются; они не являются восстанавливаемым проектом.
5. Root освобождает committed PROJECT usage только по primary DELETED proof.
   Повтор после потери ответа не даёт второй эффект. Лишь затем Operation
   становится SUCCEEDED. Новый UUID проекта получает собственный lifetime counter.

Между сетевыми вызовами нет открытой coordinator SQL-транзакции. Локальные
fences используют ограниченные advisory lock buckets; количество locks не
растёт с числом проектов в bulk-запросе. Транзакционные row locks и retained
receipts сериализуют повторы. Identity-таблицы сохраняют composite FK журналов
после удаления содержимого. FORCE RLS и явные tenant predicates действуют на
новых таблицах; metadata вложений допускает DELETE только через дополнительный
SQL proof guard по принятому intent и terminal object receipt, без UPDATE/TRUNCATE.

Ошибка сохраняется ограниченным кодом, без текста исключения и object keys.
В `Operation.failureCode` временная недоступность отображается как
`DEPENDENCY_UNAVAILABLE`, отсутствующий ресурс — `RESOURCE_NOT_FOUND`,
нарушение целостности или исходного receipt — `INVALID_STATE`. Необходимость
ручного разбора отдельно задаёт состояние `MANUAL_REVIEW`.
Неизвестный primary receipt или DATA_LOSS требуют ручного разбора
(MANUAL_REVIEW); ни timeout, ни отсутствие receipt не освобождают квоту.
Исправленный dependency может быть повторно проверен worker; нельзя подменять
receipt, снимать fence или уменьшать counters вручную ради завершения.

## Активация

Сначала миграции и RPC владельцев issue/workflow/root, затем project coordinator
и gateway. Применённые changesets не переписываются. Для нового кода требуется
согласованная версия proto и managed trust; legacy profile не активирует cleanup.

`taska.projects.deletion-tenants` — явная ограниченная worklist UUID (не более
100), по умолчанию пустая. `taska.projects.deletion-interval` по умолчанию 30s.
Каждая попытка получает свежий SERVICE context purpose `PROJECT_CLEANUP`,
serviceIdentity `project-service`, scopes `write`, `project:delete`.

Нужны точные edges:

- project → project: RecoverDeletion / recoverProjectDeletion;
- project → issue и workflow: CleanupProject / cleanupProjectResources;
- issue и workflow → project: InspectDeletion / inspectProjectDeletion;
- project → root: GetQuotaReservation / getQuotaReservation,
  ReleaseProjectUsage / releaseProjectUsage;
- root → project: Inspect / projectQuotaInspect, Inventory / projectQuotaInventory.

SERVICE identity `project-service` сохраняется на делегированных hops. Для
storage продолжения нужен существующий набор `STORAGE_CLEANUP` с identity
`issue-service` и `write`, `storage:recover`. Публичные DeleteProject/GetDeletion
требуют пользовательские signed edges gateway → project и текущую primary
actor authority. Отсутствие edge закрывает операцию, не включает fallback.

Не откатывать схемы и не запускать старый код поверх незавершённого cleanup.
Оператор читает operation/owner receipts и bounded codes, восстанавливает
зависимость и повторяет существующее продолжение. Общие DB/bucket cleanup,
очистка соседних проектов и создание фиктивного DELETED proof запрещены.

## Проверка

Изолированный runner `python3 scripts/harness/project_deletion_e2e.py` создаёт
свои actors/компании/проекты, использует REST/mTLS/JWS/PostgreSQL/S3, проверяет
отказы, удержание квоты при неизвестном исходе, restart, потерю финального ACK,
физическую очистку, чужие данные/аватар, tombstone и новый counter. Отчёты и
состояние cleanup находятся в ignored target; факт запуска не означает PASS.
Shared stage, deploy и frontend этим runner не проверяются.
