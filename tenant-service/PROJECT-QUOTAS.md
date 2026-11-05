# Квота проектов (TAS-324)

`ProjectQuotaStore` — root authority в tenant-service. `ProjectQuotaOwner` —
первичный владелец проектов в project-service. Между ними нет общей SQL-транзакции.
Общий протокол и signed SERVICE/mTLS adapters находятся в `grpc-common-lib/ru/taska/quota`.

Порядок создания:

1. `reserve(operationId, resourceId, expiresAt)` сериализуется tenant row lock.
   Текущая policy проверяет actor, tenant, lifecycle, project-create flag и scoped
   admin grant; billing даёт текущий catalog limit. TTL ограничен `Grant.maximumTtl`
   из серверной policy. В durable reservation сохраняются исходные deadline,
   policy/entitlement versions и request hash. Original actor, credential kind,
   grant ID и факт bypass сохраняются для аудита; identity/proof fields неизменяемы
   триггером, raw token/команда туда не попадают. Все PROJECT reservations имеют amount=1.
2. Owner проверяет primary reservation **до** своей SQL-транзакции, затем создаёт
   скрытый `PROVISIONING` проект, creator membership, immutable receipt и metadata
   `ProjectProvisioned` outbox в одной owner-транзакции. Повтор с другим payload
   конфликтует. Creator должен совпадать с текущим verified human actor.
3. Root подтверждает matching owner receipt и неистёкший deadline, однократно
   переносит reservation в COMMITTED и добавляет usage effect в своей транзакции.
4. После root commit owner повторно читает primary COMMITTED proof и открывает тот
   же проект, записывая `ProjectAdmissionOpened` metadata outbox однократно. Эти
   внутренние события не подменяют публичные domain events интеграции TAS-340.

Потеря ответа не разрешает новый operationId/reservation. `GetQuotaReservation`
возвращает durable outcome. После сбоя до открытия retry commit открывает прежний
проект, даже если исходный deadline уже прошёл или project-create flag выключен.
Новый create при false отклоняется до reservation; accepted replay всё равно
проходит текущую identity/purpose проверку. Снятый membership не наследует старый grant.

## Отмена, expiry и reconciliation

Сам deadline не освобождает удалённый резерв. Root держит tenant lock, получает от
owner durable CANCELLED receipt и только затем ставит RELEASED/EXPIRED. Owner
сериализует receipt lock даже при отсутствии проекта: tombstone не даёт позднему
provision создать ресурс после освобождения места. Скрытый aggregate удаляется
вместе с собственным membership; видимый проект так отменить нельзя. При отказе
owner reservation остаётся занятой. `reconcile` обрабатывает scoped batch до 100
ACTIVE записей и COMMITTED без подтверждения открытия; неизвестный outcome не
превращается в свободное место. После root commit reconciliation открывает прежние
COMMITTED проекты и подтверждает durable opened marker, не требуя повтора клиента.
Уже подтверждённые операции не занимают первые 100 позиций следующего batch.

Owner никогда не запрашивает quota authority под своей SQL-блокировкой. Root
вызывает `open` после завершения root SQL commit. Lock order: tenant row → root
quota records; в owner DB — operation advisory lock → receipt → project. Внешние
вызовы имеют deadline 5s. Policies должны выполнять current reads с ограниченным
временем, не запускать вложенную mutation/worker и не входить обратно в эту saga.

Usage — объединение полного primary inventory OPEN проектов и committed effects,
которые ещё не освобождены. PROVISIONING без root commit входит в reserved, а не
used. COMMITTED, ещё не открытый проект занимает used. Архив остаётся OPEN и
занимает место. Неучтённый прежний OPEN project попадает в usage при reconciliation.
Inventory выше 10000 IDs отклоняется целиком; усечённая страница не принимается
как полный счётчик. При недоступности inventory нет fallback на 0.

Только durable owner DELETED receipt после физического удаления позволяет
`releaseUsage`; effect уникален и повтор не уменьшает usage второй раз. TAS-459
присоединяет `recordDeleted` к своей cleanup-транзакции, сохраняя receipt после
удаления business resource. Новый проект получает новый UUID; старый operation
или receipt не используется заново.

## Контракт и активация

`QuotaAuthorityService` реализует ReserveQuota/CommitQuota/ReleaseQuota и внутренний
GetQuotaReservation. Первые поля DTO соответствуют blueprint; additive поля
resource_id/fence и внутренний expires_at фиксируют identity/generation/deadline.
`ProjectQuotaOwnerService` реализует scoped Inspect/Cancel/Open/Inventory. Все
методы требуют проверенного service identity и текущей method/purpose policy;
наличие сертификата не заменяет policy. Request context и полное тело подписаны.
При делегировании сохраняются исходные actor, credential kind, generations, scopes
и admin grant; меняются issuer/audience/action и подпись. USER_PAT не превращается
в SERVICE, scopes не расширяются. SERVICE workers должны получить отдельную
проверенную purpose-bound identity, а не наследовать права человека по UUID.

`GetTenantQuotas` добавляет PROJECT к SEAT/TASK_LIFETIME. PROJECT usage читается
до блокировки SEAT snapshot, root numeric version наблюдает owner quota version
и отклоняет регрессию. Лимит результата берётся из того же billing snapshot,
который использован для остальных квот. Unknown/malformed source не становится 0.

Настройки runtime не включают permissive policy. Stubs/security получают от
операторской transport-конфигурации; без них или current domain policies операция
закрыта с UNAVAILABLE. Public project API и полная инициализация workflow/settings,
ACL, domain events, accepted worker identity относятся к TAS-340. В профиле
`tenant-storage` отдельно могут обслуживаться только защищённые quota owner RPC;
старые project RPC остаются закрыты. Legacy профиль не переключается этим PR.

## Проверки

`ProjectQuotaIntegrationTest` поднимает две собственные PostgreSQL, применяет
доставленные миграции и использует реальные app roles/FORCE RLS. Production
root/owner handlers связаны реальным mTLS/JWS, включая обратное чтение primary
proof перед открытием. Проверяются last-slot race, повтор, expiry до записи,
owner outage, отмена скрытого проекта, потерянный ответ после root commit,
accepted-before-off retry, архив, подтверждённое удаление, A/B и scoped bypass,
конфликт payload и rollback owner outbox. Физическое удаление открытого проекта
в этом тесте моделирует оператор собственной fixture DB через cleanup; app DELETE
проверяется как запрещённый. Production hard-delete flow остаётся TAS-459.

`TenantProjectStorageIntegrationTest` проверяет новую protected receipt migration;
SEAT/membership integration tests повторяют существующие чтения GetTenantQuotas.
Изолированные сценарии не доказывают публичный REST, Kafka-доставку или общий stage.
