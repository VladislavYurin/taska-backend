# Lifetime-квота задач (TAS-460)

В opt-in профиле `tenant-storage` `TenantIssueStore` создаёт issue, выделяет номер,
увеличивает `project_counters.lifetime_created` (int64), сохраняет историю,
идемпотентный ответ и обе outbox-записи в одной транзакции issue DB.
Все типы задач расходуют одно создание. Paid с `unlimited=true` продолжает считать.
Проверки удаления, статуса, типа, архивации, имени и календарного месяца не уменьшают counter.
Старый конструктор без quota policy закрывает create с `UNAVAILABLE`.

`LifetimeTaskQuota.Authority` обязана проверять актуальную identity, membership,
project ACL, lifecycle, purpose и scoped admin grant. Bypass не выводится из роли
в DTO. Проверка повторяется после ожидания tenant admission lock; replay также
требует текущих полномочий, но не нового billing lookup или увеличения счётчика.
Billing `EntitlementProjection.Source` вызывается для каждого нового создания,
под блокировкой tenant quota state, с deadline 5 секунд и version fence. Кешированное
разрешение, просроченный snapshot и отсутствие policy/source не разрешают запись.
GLOBAL_ADMIN с подтверждённым bypass пропускает бизнес-ограничения, сохраняя
реальный счётчик, tenant isolation, tombstone и числовые границы.

Лимит берётся из `KIND_TASK_LIFETIME` текущей версии каталога. Ровно limit
(начальный FREE — 50) запрещает только новые задачи этого проекта с
`FAILED_PRECONDITION / QUOTA_EXCEEDED`. Если максимум любого живого проекта
строго больше limit, новое обычное создание в любом проекте отклоняется как
`TENANT_READ_ONLY_OVER_QUOTA`. Текущие данные остаются. При paid→FREE это также
возвращается как `TenantTaskQuotaSummary.over_limit=true`: независимая причина
`OVER_QUOTA` для integration/evaluator (TAS-495). При восстановлении unlimited
сигнал очищается; OWNER_PAUSE, PAYMENT_DISPUTE и другие причины не очищаются.

## Чтение и границы активации

`ProjectQuotaReadService.GetProjectTaskQuota` реализует blueprint DTO: точное
использование проекта, `reserved=0`, limit/unlimited и числовую version. Authority
сначала подтверждает живой доступный проект, включая ещё не имевший задач.
`GetTenantTaskQuotaSummary` — внутренний RPC для агрегата tenant: **максимум**
счётчиков живых проектов, а не сумма, версия и факт превышения без списка project ID.
Сервисный read требует текущей authority issuer/action/tenant; наличие mTLS само
по себе не даёт доступа. `TenantServerInterceptor` принимает только эти два RPC
при настроенном `RpcContextSecurity`; остальные старые issue RPC остаются закрыты.

`tenant-service.GetTenantQuotas` добавляет `KIND_TASK_LIFETIME` с этим максимумом,
без `project_id`. `GrpcTenantTaskQuotaSource` использует подписанный SERVICE context
и mTLS stub. Read source вызывается до блокировки tenant DB, чтобы не создавать
RPC-цикл с membership. Нет fallback на неизвестное использование 0. Корневая
числовая version меняется при наблюдении новой owner version; регрессия owner
version или billing версии относительно summary отклоняется.

Миграция issue `0017` добавляет FORCE RLS state/outbox и `retired_at` для counters;
применённые `0015/0016` не меняются. Триггер запрещает уменьшение или удаление
счётчика и восстановление retired UUID. `retire` — hook для TAS-459: authority
обязана подтвердить durable receipt **физического** удаления проекта, операция
присоединяется к cleanup-транзакции issue DB. Архивирование его не вызывает.
Даже проект без созданий получает tombstone; новый UUID начинает с нуля.
Cleanup не зависит от billing availability: сохраняет последнюю коммерческую версию,
а при её отсутствии outbox содержит entitlement_version=0 / over_limit=NULL.
Это неизвестные условия, которые consumer обязан уточнить, без permissive FREE/paid fallback.

`task_quota_outbox` — отдельный append-only transactional outbox с tenant, numeric
versions, maximum и over_limit; private issue contents в нём нет. Issue outbox
сохраняет прежний FK к существующему issue. Подключение publisher/consumer сигнала
OVER_QUOTA и проверки всех остальных content writes выполняется в TAS-495;
активация tenant create handlers — TAS-343, owner-confirmed deletion — TAS-459.
Здесь нет фонового worker с фиктивным tenant или permissive production policy.

## Проверки

`TenantIssueStorageIntegrationTest`: реальный PostgreSQL/app role/FORCE RLS,
гонка 49→50, replay без billing, rollback всех таблиц/outbox, all types/paid,
удалённые задачи, другой месяц, 50 против >50, bypass, независимые restrictions,
retirement, новый UUID, scope и fail-closed policy.
`SeatQuotaIntegrationTest`: агрегат TASK, numeric versions, rollback stale owner
version и отсутствие fallback на 0. `GrpcTaskQuotaSourceTest`: реальный mTLS/JWS,
tenant binding и отзыв текущей service authority.
Это изолированные backend проверки; публичный REST, Kafka-доставка и общий stage
данными тестами не подтверждаются.

## Lock order и эксплуатационные версии

Порядок: idempotency claim → tenant quota state → current authority → current
billing → project counter. Предварительная authority выполняется до claim;
повторная после ожидания lock нужна против отозванного grant. Authority/source
должны выполнять неблокирующие current reads с deadline 5 секунд, без мутаций,
PSP, фоновой работы или обратного входа в issue DB. TAS-459 сохраняет порядок
quota state → counter. Индекс `(tenant_id,lifetime_created DESC) WHERE retired_at
IS NULL` обслуживает MAX без сканирования всех проектов.

Owner version монотонна и долговечна. Отдельно пересоздавать/откатывать issue DB
при сохранении более новой tenant DB нельзя. При disaster recovery оператор
должен сверить исходные counters/receipts и восстановить согласованный backup
или монотонно продвинуть корректную owner projection перед возобновлением traffic.
Сброс version или quota usage административным HTTP-запросом не предоставляется;
`TASK_QUOTA_STALE` сохраняет отказ до восстановления согласованности.
