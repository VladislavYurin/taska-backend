# tenant-service — TAS-310

Сервис владеет реестром компаний. `TenantService.CreateTenant/GetTenant` реализуют подмножество принятого [B2B protobuf](../docs/b2b/contracts/b2b.proto), опубликованное в `grpc-common-lib/src/main/proto/v1/tenant-registry.proto` с сохранением имён и номеров полей. Публичные HTTP endpoints ещё не подключены: gateway должен сначала получить глобальные/tenant session adapters и provisioning flow. Статический REST OpenAPI существующей системы не расширяется недоступным маршрутом.

## Создание и повторы

Create принимает только проверенный контекст `WEB_GLOBAL` с actor/session, без tenant и с action `createTenant`. Body не задаёт владельца, lifecycle, стоимость или deadline. `PublishedPlans` возвращает только опубликованную публичную immutable версию тарифа (adapter — TAS-313); client planVersion UUID сам по себе не доказывает его доступность. `FreeAdmission.reserve` — локальная атомарная операция TAS-472 на переданном DatabaseClient, не удалённая проверка. Без этих providers создание отклоняется до commit; paid никогда не вызывает FREE admission.

Для входной глобальной authority `TenantCreationTransaction` устанавливает только локальный tenant scope для записи registry; это не выпуск сессии и не выдача прав компании. Выполнение идёт через общий TenantTransactionExecutor: set_config transaction-local, одна connection, commit перед ответом, rollback/cancel cleanup. Каталог разрешается между короткими транзакциями, вне pooled transaction; inside callback нет сетевого RPC.

Стабильный UUIDv8 компании получается из SHA-256 доменного namespace `taska:createTenant:v1`, actor UUID и UUID Idempotency-Key (length-prefixed UTF-8; первые 128 бит с UUID version/variant). Алгоритм после публикации не меняется: это durable routing для pre-tenant команды без глобального каталога/блокировок или nullable tenant в dedupe. Key остаётся scoped к actor. UUID не является секретом или механизмом авторизации.

В tenant row сохраняются immutable `creation_actor_id`, `creation_request_hash` (SHA-256 canonical JSON name/planVersionId/operationId), `creation_operation_id`, `plan_version_id`, kind и deadline. `INSERT ON CONFLICT` сериализует параллельные попытки: разные payload с тем же ключом дают IDEMPOTENCY_CONFLICT, один payload возвращает один tenant/operation. Replay проверяет текущую ACTIVE membership, работает при retirement/недоступности каталога и после удаления response-cache. TTL idempotency cache24h не удаляет durable creation facts; их purge должен учитывать доменный dedupe в TAS-520.

В одной транзакции создаются tenant, ACTIVE/TENANT_ADMIN owner membership, outbox `TenantCreationRequested` и idempotency response. Циклический composite FK owner→membership проверяется на commit. Event содержит IDs/planVersion, не название компании или credentials. Publisher и возобновляемая saga — TAS-318/337. Операция возвращается PENDING: readiness/ACTIVE этим модулем не выставляется.

Paid начинается AWAITING_PAYMENT с `creationDeadline=createdAt+30d`, `PENDING_FIRST_PAYMENT`, без покрытия/рабочих прав. FREE остаётся PROVISIONING после атомарного admission, без paid deadline. Retrying не продлевает deadline. Trigger запрещает изменение исходных creation facts даже прямым SQL. Get требует совпадение signed tenant и request tenant, action getTenant и ACTIVE membership; чужой/удалённый tenant скрывается как TENANT_NOT_FOUND. Это чтение metadata, оно не обходит ограничения рабочих операций. Административный bypass — отдельный доверенный путь TAS-346, обычный GLOBAL_ADMIN не даёт его автоматически.

## Trust и запуск

`RpcContextSecurity` должен получить настоящие Keys, shared ReplayGuard и online Authority adapters TAS-306. Authority обязан привязать исходную сессию к зарегистрированному действующему пользователю, RPC/action/scope и актуальным generations; одного наличия account недостаточно. TenantServerInterceptor проверяет message-bound JWS и mTLS SPIFFE peer; только два метода внесены в allowlist. Без trust provider все gRPC calls получают UNAVAILABLE до handler, plaintext не становится fallback. Liveness/DB health доступны отдельно. Этот foundation ещё не означает доступный self-service на общем стенде.

База выделена сервису. До первого старта оператор выполняет:

```sh
psql -X -v ON_ERROR_STOP=1 -f infra/tenant/bootstrap.psql
```

PGHOST/PGDATABASE/PGUSER и credential store задаются оператором извне. Bootstrap общий TAS-308 создаёт NOLOGIN migrator, LOGIN deployer NOINHERIT с разрешённым SET ROLE и непривилегированный app; отдельная закрытая `tenant_liquibase` принадлежит migrator. Пароли provision отдельно. В приложении Liquibase подключается deployer и явно переключается на migrator; runtime использует только app. Перед регистрацией бизнес-service startup guard отвергает owner/superuser/BYPASSRLS/role memberships. Новая миграция создаёт шесть таблиц TAS-310, применяет shared FORCE RLS templates к пяти tenant tables; global_outbox не получает app grants. Journal недоступен runtime; demo accounts нет.

Переменные: `TENANT_SPRING_R2DBC_{URL,USERNAME,PASSWORD}`, `TENANT_SPRING_LIQUIBASE_{URL,USERNAME,PASSWORD}`, `TENANT_{SERVER_PORT,GRPC_SERVER_PORT}` (8089/9099). Секреты обязательны, штатных паролей нет. Для отдельного локального Compose заполнить три `TENANT_{OPERATOR,APP,DEPLOY}_PASSWORD` в приватном окружении, затем:

```sh
docker compose -p taska-tenant-local -f infra/tenant/compose.yml up -d --build
```

Compose поднимает исключительно свои PostgreSQL/service, доступные по loopback. Инициализация выполняется только для новой private DB. Этот stack не включён в общий deploy-stage: активация foundation до подключения downstream/trust не является задачей TAS-310. Не монтировать shared production data directory. Для остановки своего stack: `docker compose -p taska-tenant-local -f infra/tenant/compose.yml down`.

## Проверки

```sh
python3 scripts/harness.py check --module tenant-service
```

PostgreSQL16/Testcontainers, фактический Liquibase с deployer→migrator, app LOGIN и повтор применения changelog. Проверяются atomic create, конкурентный retry/conflict, immutable deadline, cache eviction и catalog retirement, независимые actors, FREE/paid separation, cancellation rollback, membership isolation, отсутствующая trust и запуск Spring Boot/HTTP health на чистой миграции. `RegistryTestContext` существует только в test sources; production не принимает body context за verified authority. Дополнительно сам registry adapter проверяется по настоящему loopback mTLS/JWS: create/get, payload tampering, replay и чужой actor; openssl создаёт временные credentials. Базовые security-инварианты повторяются grpc-common-lib suite. Runtime jar не содержит test helper. Планы и service repository контракты будущих сущностей проверяются задачами их реализации.
